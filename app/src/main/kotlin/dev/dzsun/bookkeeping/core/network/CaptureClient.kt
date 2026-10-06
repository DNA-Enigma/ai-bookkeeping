package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 一次「拍/说 → 结构化字段」的完整往返。
 *
 * 把 [DispatcherClient] 的六个端点按契约要求的顺序串起来：上传媒体 → 提交任务 →
 * 订阅事件流到终态 → （需要时）取快照拿澄清。上层界面只看到 [CaptureOutcome]。
 *
 * 用事件流而不是轮询快照，是因为流能拿到逐节点的中间产物——这正是
 * [CaptureOutcome.Failed] 还能带上 `partialFields` 的原因。
 */
@Singleton
class CaptureClient @Inject constructor(
    private val client: DispatcherClient,
    private val config: DispatcherConfig,
    private val ids: IdGenerator,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun capture(request: CaptureRequest): CaptureOutcome = withTimeout(CAPTURE_TIMEOUT_MS) {
        val mediaRef = request.media?.let { payload ->
            val uploaded = client.uploadMedia(payload.bytes, payload.mime, payload.role)
            MediaRef(
                mediaId = uploaded.mediaId,
                kind = uploaded.kind,
                mime = uploaded.mime,
                bytes = uploaded.bytes,
                sha256 = uploaded.sha256,
                role = payload.role.wireName,
                capabilities = listOf(CAPABILITY_VISION_EXTRACT),
            )
        }

        val accepted = client.submitTask(
            TaskEnvelope(
                requestId = ids.newId(),
                // 有图片时用它的 sha256 做幂等键：同一张截图重复提交落到同一个任务，
                // 而不是入两次账。这也是契约示例里 `bookkeeping:u_123:sha256-…` 的用意。
                idempotencyKey = idempotencyKeyFor(mediaRef),
                identity = Identity(userId = config.userId),
                input = TaskInput(text = request.text, media = mediaRef?.let { listOf(it) }),
                declared = Declared(intent = request.intent, authoritative = true),
                constraints = Constraints(dataSensitivity = Constraints.SENSITIVITY_FINANCIAL),
                client = ClientInfo(
                    app = CLIENT_APP,
                    appVersion = BuildConfig.VERSION_NAME,
                    supportsSse = true,
                    // 移动端必须为 true：退后台、切网、锁屏都会断连而任务仍在跑
                    supportsLastEventId = true,
                ),
            ),
        )

        collectOutcome(accepted.taskId)
    }

    /**
     * 边收事件边攒中间产物，直到终态或需要澄清。
     *
     * `first {}` 会在命中后取消上游，SSE 连接由 [DispatcherClient] 的
     * `invokeOnCompletion` 断开——所以这里不会漏连接。
     */
    private suspend fun collectOutcome(taskId: String): CaptureOutcome {
        var fields: ReceiptFields? = null

        val terminal = client.eventStream(taskId)
            .onEach { event ->
                event.parseReceiptFields()?.let { fields = it }
            }
            .first { it.type in STOP_EVENT_TYPES }

        return when (terminal.type) {
            TaskEvent.TYPE_CLARIFICATION_NEEDED -> {
                // 澄清问题的权威形状在快照里（事件载荷只是提示），所以取一次快照
                val snapshot = client.getTask(taskId)
                val clarification = snapshot.clarification
                if (clarification == null) {
                    CaptureOutcome.Failed(taskId, snapshot.error, fields)
                } else {
                    CaptureOutcome.NeedsClarification(taskId, clarification, fields)
                }
            }

            TaskEvent.TYPE_TASK_COMPLETED -> {
                val snapshot = client.getTask(taskId)
                val resolved = fields ?: snapshot.receiptFieldsFromArtifacts()
                if (resolved == null) {
                    CaptureOutcome.Failed(
                        taskId,
                        Problem(
                            type = "about:blank",
                            title = "任务成功但没有可用的抽取结果",
                            status = 200,
                            code = "missing_artifacts",
                            retryable = false,
                        ),
                        null,
                    )
                } else {
                    CaptureOutcome.Completed(taskId, resolved)
                }
            }

            else -> {
                val snapshot = runCatching { client.getTask(taskId) }.getOrNull()
                CaptureOutcome.Failed(taskId, snapshot?.error, fields)
            }
        }
    }

    private fun idempotencyKeyFor(mediaRef: MediaRef?): String {
        val discriminator = mediaRef?.sha256?.let { "sha256-$it" } ?: "req-${ids.newId()}"
        return "bookkeeping:${config.userId}:$discriminator"
    }

    private fun TaskEvent.parseReceiptFields(): ReceiptFields? {
        if (type != TYPE_SUBTASK_COMPLETED) return null
        val output = payload.subtaskOutput(SUBTASK_EXTRACT) ?: return null
        return runCatching { json.decodeFromJsonElement(ReceiptFields.serializer(), output) }.getOrNull()
    }

    /**
     * 终态快照里也可能带着产物。契约说失败时保留已完成的产物，
     * 而实测中 `extract` 成功了却因为下游超时整单失败——这条路必须能兜住。
     */
    private fun TaskSnapshot.receiptFieldsFromArtifacts(): ReceiptFields? {
        val artifacts = artifacts ?: return null
        val candidate = artifacts[SUBTASK_EXTRACT]?.let { it as? JsonObject } ?: artifacts
        return runCatching { json.decodeFromJsonElement(ReceiptFields.serializer(), candidate) }.getOrNull()
    }

    private companion object {
        const val CLIENT_APP = "bookkeeping"
        const val CAPABILITY_VISION_EXTRACT = "vision.extract"
        const val SUBTASK_EXTRACT = "extract"

        /** 事件类型来自 `schemas/event.json` 的开放集合，未知类型一律忽略。 */
        const val TYPE_SUBTASK_COMPLETED = "subtask.completed"

        /**
         * 上限取 5 分钟：实测 `extract` 一个节点就跑了 31.7 秒（模板里却写着 20 秒超时），
         * 整条链的正常耗时不可低估。
         */
        const val CAPTURE_TIMEOUT_MS = 5 * 60 * 1000L

        val STOP_EVENT_TYPES = setOf(
            TaskEvent.TYPE_CLARIFICATION_NEEDED,
            TaskEvent.TYPE_TASK_COMPLETED,
            TaskEvent.TYPE_TASK_FAILED,
            TaskEvent.TYPE_TASK_CANCELLED,
        )
    }
}
