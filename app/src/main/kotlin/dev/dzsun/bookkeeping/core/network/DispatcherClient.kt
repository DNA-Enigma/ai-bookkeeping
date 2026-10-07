package dev.dzsun.bookkeeping.core.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 调度层的 HTTP 客户端。
 *
 * 用 [HttpURLConnection] 而不是 OkHttp：契约需要的六件事（媒体上传、提交任务、
 * 取快照、SSE、澄清答复、反馈回报）它都能做，且**不引入新依赖**——
 * 在一个由多方并行修改、尚无版本控制的构建里，少动一次 `build.gradle.kts` 就少一次冲突。
 * 若日后需要 HTTP/2、连接池调优或拦截器，再换 OkHttp 不迟，接口不用变。
 */
@Singleton
class DispatcherClient @Inject constructor(
    private val config: DispatcherConfig,
) {

    private val json = Json {
        // 契约承诺只做加法（新增可选字段），而手机无法强制升级 —— 必须容忍不认识的字段
        ignoreUnknownKeys = true
        // 不把 null 发出去，但**默认值要发**：
        // supports_last_event_id 这类字段若被省略，服务端会按它自己的默认处理，我们就失去了声明能力
        explicitNulls = false
        encodeDefaults = true
    }

    // ------------------------------------------------------------ 媒体

    /**
     * 上传媒体，拿到 [MediaRef] 用的 `media_id` 与 `sha256`。
     *
     * MIME 与大小在**任何模型开销之前**校验：超限返 413、类型不支持返 415。
     * 因此调用方应先自行压缩并转成 JPEG/WebP —— 契约的 `allowed_mime` 只有
     * jpeg / png / webp，而部分安卓机型相机默认输出 HEIF。
     */
    suspend fun uploadMedia(bytes: ByteArray, mime: String, role: MediaRole? = null): MediaUpload {
        val headers = buildMap { role?.let { put(HEADER_MEDIA_ROLE, it.wireName) } }
        val body = execute(
            method = "POST",
            path = "/v1/media",
            body = bytes,
            contentType = mime,
            headers = headers,
        )
        return json.decodeFromString(body.decodeToString())
    }

    // ------------------------------------------------------------ 任务

    /**
     * 提交任务。
     *
     * 契约里幂等键在请求体（`TaskEnvelope.idempotency_key`），文档里又在请求头
     * （`Idempotency-Key`）。两处都发同一个值是稳妥做法，等后端确认后再收敛到一处。
     */
    suspend fun submitTask(envelope: TaskEnvelope): TaskAccepted {
        val headers = buildMap {
            envelope.idempotencyKey?.let { put(HEADER_IDEMPOTENCY_KEY, it) }
        }
        val body = execute(
            method = "POST",
            path = "/v1/tasks",
            body = json.encodeToString(envelope).encodeToByteArray(),
            contentType = CONTENT_TYPE_JSON,
            headers = headers,
        )
        return json.decodeFromString(body.decodeToString())
    }

    suspend fun getTask(taskId: String): TaskSnapshot =
        json.decodeFromString(execute("GET", "/v1/tasks/$taskId").decodeToString())

    /** 终态产物。任务未到终态时服务端返 409 `result_not_ready`（可重试）。 */
    suspend fun getResult(taskId: String): JsonObject =
        json.decodeFromString(execute("GET", "/v1/tasks/$taskId/result").decodeToString())

    /**
     * 答复澄清问题以恢复任务。
     *
     * [ClarificationAnswer.edits] 务必填全——它是自进化最有价值的输入：
     * 同时给出错在哪（field）和对的是什么（to），等于一条带真值的标注。
     */
    suspend fun clarify(taskId: String, answer: ClarificationAnswer) {
        execute(
            method = "POST",
            path = "/v1/tasks/$taskId/clarify",
            body = json.encodeToString(answer).encodeToByteArray(),
            contentType = CONTENT_TYPE_JSON,
        )
    }

    /** 幂等：任务已在终态时服务端仍返回 204。 */
    suspend fun cancel(taskId: String) {
        execute("POST", "/v1/tasks/$taskId/cancel")
    }

    /** 人工质量信号，采集点是确认/修改页。 */
    suspend fun feedback(taskId: String, feedback: TaskFeedback) {
        execute(
            method = "POST",
            path = "/v1/tasks/$taskId/feedback",
            body = json.encodeToString(feedback).encodeToByteArray(),
            contentType = CONTENT_TYPE_JSON,
        )
    }

    // ------------------------------------------------------------ 事件流

    /**
     * 订阅事件流，断线时带 [lastEventId] 重放。
     *
     * **这是移动端的必需能力**：应用退到后台、网络切换、锁屏都会断连，
     * 而任务仍在服务端继续执行。契约因此要求事件日志持久，客户端重连时
     * 先从 `seq+1` 重放再切实时推送。
     *
     * 调用方负责重连策略（指数退避 + 抖动）；本函数只负责一次连接的生命周期。
     * 未知事件类型照常发出，由消费方决定忽略——契约只做加法，不能因为多了一种事件就崩。
     *
     * @param lastEventId 已消费到的最大 `seq`；null 表示从头开始。
     */
    fun eventStream(taskId: String, lastEventId: Long? = null): Flow<TaskEvent> = flow {
        val headers = buildMap {
            put("Accept", CONTENT_TYPE_EVENT_STREAM)
            // 契约：Last-Event-ID 与 ?since= 同时提供时以前者为准
            lastEventId?.let { put(HEADER_LAST_EVENT_ID, it.toString()) }
        }
        val connection = try {
            open("GET", "/v1/tasks/$taskId/events", headers, readTimeoutMs = STREAM_READ_TIMEOUT_MS)
        } catch (e: IOException) {
            throw DispatcherException(null, "无法连接事件流", e)
        }
        // readLine() 是阻塞的：协程被取消时它不会自己返回，finally 里的 disconnect 就永远轮不到。
        // 所以在协程结束时主动断连，把阻塞的读操作从 socket 层面打断——否则每次退出界面都漏一个连接。
        val onCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { connection.disconnect() }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw problemFrom(status, connection.errorStream?.readBytes() ?: ByteArray(0))
            }
            // 逐帧解析：空行是帧分隔符，以 ':' 开头的是注释（心跳用它保活）。
            // 用 readLine 而不是 useLines —— 后者的 lambda 不是 suspend 的，里面 emit 不了。
            connection.inputStream.bufferedReader().use { reader ->
                var type = DEFAULT_EVENT_TYPE
                var seq: Long? = null
                val data = StringBuilder()

                fun takeFrame(): TaskEvent? {
                    if (data.isEmpty()) return null
                    val frame = TaskEvent(
                        // 没有 id 的帧无法参与重放，但内容仍要送达，故退化为 0
                        seq = seq ?: 0L,
                        type = type,
                        payload = runCatching { json.decodeFromString<JsonObject>(data.toString()) }
                            .getOrElse { JsonObject(emptyMap()) },
                    )
                    type = DEFAULT_EVENT_TYPE
                    seq = null
                    data.clear()
                    return frame
                }

                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> takeFrame()?.let { emit(it) }

                        line.startsWith(":") -> Unit // 注释 / 心跳

                        line.startsWith("event:") -> type = line.removePrefix("event:").trim()

                        line.startsWith("id:") -> seq = line.removePrefix("id:").trim().toLongOrNull()

                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }

                        else -> Unit // retry: 等其它字段；重连节奏由调用方控制
                    }
                }
                takeFrame()?.let { emit(it) }
            }
        } finally {
            onCancel?.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------ 传输

    private suspend fun execute(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = try {
            open(method, path, headers, readTimeoutMs = REQUEST_READ_TIMEOUT_MS)
        } catch (e: IOException) {
            // 原始信息是「无法连接 <baseUrl><path>」——它带着服务端地址与路由，
            // **不进界面**（见 UserFacingErrors）。排查靠 cause 里的 IOException。
            throw DispatcherException(null, UserFacingErrors.UNREACHABLE, e)
        }
        try {
            if (body != null) {
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.readBytes() ?: ByteArray(0)
            if (status !in 200..299) throw problemFrom(status, responseBody)
            responseBody
        } catch (e: IOException) {
            throw DispatcherException(null, "请求 $path 失败", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        method: String,
        path: String,
        headers: Map<String, String>,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val connection = URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", CONTENT_TYPE_JSON)
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        // 契约只声明了全局 bearerAuth(JWT)，但没有发放 token 的端点 —— 见 DispatcherConfig
        config.bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    /**
     * 把错误响应还原成有类型的 [Problem]。
     *
     * 响应体不是合法 Problem 时**不假装它是传输失败**——那会让调用方
     * 对一个 400 盲目重试。这里合成一个 code 为 `untyped_error` 的 Problem，
     * 并只按 HTTP 语义决定可重试性。
     */
    private fun problemFrom(httpStatus: Int, body: ByteArray): DispatcherException {
        val decoded = runCatching { json.decodeFromString<Problem>(body.decodeToString()) }.getOrNull()
        val problem = decoded ?: Problem(
            type = "about:blank",
            title = "HTTP $httpStatus",
            status = httpStatus,
            code = "untyped_error",
            retryable = httpStatus >= 500,
            detail = body.decodeToString().take(MAX_DETAIL_CHARS).ifBlank { null },
        )
        return DispatcherException(problem, problem.detail ?: problem.title)
    }

    private companion object {
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_EVENT_STREAM = "text/event-stream"
        const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        const val HEADER_LAST_EVENT_ID = "Last-Event-ID"
        const val HEADER_MEDIA_ROLE = "X-Media-Role"

        /** SSE 规范：没有 event 字段时按 message 处理。 */
        const val DEFAULT_EVENT_TYPE = "message"

        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * 读超时。**这个值比看起来该有的大得多，是有原因的。**
         *
         * 契约把异步说成基底（`mode: async`），读起来像是「受理即返 202」。
         * 但实测 `POST /v1/tasks` 会**一直等到评估 + 路由 + 拆解跑完才返回**——
         * 一次提交票据任务实测耗时 **81 秒**（还是重试了两次拆解之后失败的那种）。
         * 原先设 30 秒，结果任务其实提交成功了，客户端却先报「请求失败」，
         * 而服务端那边任务还在继续跑——最糟的一种错觉。
         *
         * 取 180 秒是按任务预算 `max_wall_ms: 120000` 再留余量。
         * 后端若真的改成受理即返 202，这里可以降回去。
         */
        const val REQUEST_READ_TIMEOUT_MS = 180_000

        /** 要大于服务端的 sse_heartbeat_ms（默认 15000），否则心跳还没来就被我们掐了。 */
        const val STREAM_READ_TIMEOUT_MS = 60_000

        const val MAX_DETAIL_CHARS = 500
    }
}

/** 媒体的用途提示。判定权在 01 评估器，这里只是提示。 */
enum class MediaRole(val wireName: String) {
    SOURCE_DOCUMENT("source_document"),
    SCREENSHOT("screenshot"),
    PHOTO("photo"),
    AUDIO_NOTE("audio_note"),
}
