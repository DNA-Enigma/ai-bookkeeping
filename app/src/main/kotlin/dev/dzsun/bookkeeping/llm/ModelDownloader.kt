package dev.dzsun.bookkeeping.llm

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 下载任务的**描述与期望值**——UI/管理器都只认它，不认具体 URL 字符串。
 *
 * [expectedSha256] 为空时跳过校验并**明示**（占位期的诚实策略：没给真值就说没验，
 * 不假装验过）。真实模型文件的 sha256 到位后连着 [url] 一起换。
 */
data class ModelDownloadSpec(
    val url: String,
    val fileName: String,
    val expectedSha256: String = "",
)

/** 下载结果。**半截文件绝不叫正式名**——成功路径之外 `.part` 一律不留。 */
sealed interface ModelDownloadResult {
    /** 下载完成且（若给了期望值）SHA256 校验通过。 */
    data class Success(val file: File, val bytes: Long, val sha256: String) : ModelDownloadResult

    /** 校验不匹配：已把 .part 删掉，由调用方决定是否重下。 */
    data class ChecksumMismatch(val expected: String, val actual: String, val bytes: Long) : ModelDownloadResult

    data class Failed(val message: String) : ModelDownloadResult
}

/**
 * GGUF 断点续传下载器——**纯 JVM 可测**（连接由 [opener] 注入）。
 *
 * ## 续传协议
 *
 *  - 目标处已有完整文件（大小 == 总长，总长未知时看不了）→ 不下载直接成功；
 *  - 有 `.part` → 带 `Range: bytes=<已下>-` 重开：**206** = 接着写；
 *    **200** = 服务器不支持 Range，弃 `.part` 重头；
 *  - 没有 `.part` → 全新 GET。
 *
 * ## 纪律（与 [ModelInstaller] 同一套，理由不重复展开）
 *
 *  1. 先写 `.part`，SHA256 通过后才改名——残缺模型不许占用正式文件名；
 *  2. SHA256 **边下边算**（流式），不为校验再读一遍 1 GB；
 *  3. 任何失败清掉 `.part`（校验不匹配除外——它由调用方决定重试策略，
 *     但 [ModelDownloader] 自己不删，因为调用方可能想先看看实际哈希）。
 *
 * ⚠️ 但「断点续传」与「失败清 .part」对**网络中断**是矛盾的：下到一半断网，
 * `.part` 就是续传的全部本钱，清了等于白下。所以网络类失败**保留 `.part`**，
 * 只清「确定写坏了」的场景（本地 IO 异常除外——同上保留）。
 * 真正要清的只有一种情况：服务器 200 全量重发（旧 .part 内容对新请求无意义）。
 */
class ModelDownloader(
    private val targetDir: File,
    private val opener: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val freeSpace: () -> Long = { targetDir.usableSpace },
) {

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        /** 期望的续传响应码：206 Partial Content。 */
        private const val HTTP_PARTIAL = 206
        private const val HTTP_OK = 200

        /** 取消信号走 Failed 通道传回管理器；管理器按消息识别（见 ModelDownloadManager.start）。 */
        const val CANCELLED = "__cancelled__"
        const val REPORT_EVERY_BYTES = 4L * 1024 * 1024

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** .part 文件；管理器与测试都要看它多大（决定下次 Range 起点）。 */
    fun partFile(spec: ModelDownloadSpec): File = File(targetDir, "${spec.fileName}.part")

    fun targetFile(spec: ModelDownloadSpec): File = File(targetDir, spec.fileName)

    /**
     * 执行下载。[onProgress] 收**绝对字节数**（不是 0..1——续传起点非 0，
     * 百分比交给 UI 层按总长算）与总长（未知 -1）。
     *
     * @param cancelled 轮询取消信号：网络读不吞协程取消，用这个协作退出；
     *   取消时 `.part` **保留**（那就是下次续传的起点）。
     */
    fun download(
        spec: ModelDownloadSpec,
        cancelled: () -> Boolean = { false },
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): ModelDownloadResult {
        val target = targetFile(spec)
        val part = partFile(spec)

        // 已有完整文件：不碰网络（管理器在 start 前也会查，这里是防御——
        // 两次调用之间文件可能被别处放好）。哈希照实算——这个 Result 的
        // sha256 字段不许拿空串冒充「校验过了」。
        if (target.isFile && target.length() > 0) {
            val sha = target.inputStream().use { stream ->
                val d = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
                d.digest().joinToString("") { "%02x".format(it) }
            }
            return ModelDownloadResult.Success(target, target.length(), sha)
        }

        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return ModelDownloadResult.Failed("建不了模型目录：${targetDir.absolutePath}")
        }

        val connection = try {
            opener(spec.url).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            return ModelDownloadResult.Failed("打不开下载地址：${e.message ?: e.javaClass.simpleName}")
        }

        var partial = false
        try {
            var resumeFrom: Long
            if (part.isFile && part.length() > 0) {
                resumeFrom = part.length()
                connection.setRequestProperty("Range", "bytes=$resumeFrom-")
                connection.connect()
                when (connection.responseCode) {
                    HTTP_PARTIAL -> partial = true
                    // 服务器不认 Range：全量重发。旧 .part 作废，从头写
                    HTTP_OK -> {
                        partial = false
                        if (!part.delete()) {
                            return ModelDownloadResult.Failed("清不掉旧的部分文件：${part.name}")
                        }
                        resumeFrom = 0L
                    }
                    else -> return ModelDownloadResult.Failed(
                        "服务器拒绝续传请求（HTTP ${connection.responseCode}）",
                    )
                }
            } else {
                resumeFrom = 0L
                connection.connect()
                if (connection.responseCode != HTTP_OK) {
                    return ModelDownloadResult.Failed("HTTP ${connection.responseCode}：${spec.url.take(120)}")
                }
            }

            val total = contentLength(connection, resumeFrom, partial)
            if (total > 0 && freeSpace() < total - resumeFrom + SPACE_MARGIN_BYTES) {
                return ModelDownloadResult.Failed(
                    "空间不足：还差 ${mb(total - resumeFrom)} MB，可用 ${mb(freeSpace())} MB",
                )
            }

            val body = connection.inputStream ?: return ModelDownloadResult.Failed("服务器没有返回内容")

            var written = resumeFrom
            var lastReported = -1L
            val digest = MessageDigest.getInstance("SHA-256")
            // 续传时先喂已有字节——哈希必须覆盖完整文件，不能只算本次增量
            if (resumeFrom > 0) {
                part.inputStream().use { existing ->
                    val pre = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = existing.read(pre)
                        if (n < 0) break
                        digest.update(pre, 0, n)
                    }
                }
            }

            body.use { input ->
                // 续传必须 **append**（从 .part 末尾接着写）；FileOutputStream 默认构造会
                // 截断——那会把之前会话下的一大半直接抹掉。全量（200 重发）才用截断。
                java.io.FileOutputStream(part, partial).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled()) {
                            // 取消：.part 保留（续传本钱），连接由 use 关闭
                            return ModelDownloadResult.Failed(CANCELLED)
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (written - lastReported >= REPORT_EVERY_BYTES) {
                            lastReported = written
                            onProgress(written, total)
                        }
                    }
                }
            }

            onProgress(written, total)

            // 大小核对：总长已知时不许差一个字节
            if (total > 0 && written != total) {
                // .part 保留：可能只是连接断了没读到 EOF，下次续传
                return ModelDownloadResult.Failed("下载不完整：$written / $total 字节（部分已保留，重试会接着下）")
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = spec.expectedSha256.trim().lowercase()
            if (expected.isNotEmpty() && actualSha != expected) {
                // 校验不匹配：.part 交调用方处置（管理器的策略是删掉重下）
                return ModelDownloadResult.ChecksumMismatch(expected, actualSha, written)
            }

            if (target.exists() && !target.delete()) {
                return ModelDownloadResult.Failed("覆盖不掉旧文件：${target.name}")
            }
            if (!part.renameTo(target)) {
                return ModelDownloadResult.Failed("改名失败：${part.name} -> ${target.name}")
            }
            return ModelDownloadResult.Success(target, written, actualSha)
        } catch (e: IOException) {
            // 网络/IO 中断：.part 保留——它是续传的全部本钱
            return ModelDownloadResult.Failed("下载中断：${e.message ?: e.javaClass.simpleName}（重试会接着下）")
        } catch (e: SecurityException) {
            return ModelDownloadResult.Failed("写文件被拒：${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            return ModelDownloadResult.Failed("下载状态异常：${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun contentLength(connection: HttpURLConnection, resumeFrom: Long, partial: Boolean): Long {
        if (partial) {
            // 206：Content-Range: bytes start-end/total；解析 total。
            // 某些服务器给 */total 或缺 total（unknown）——都按未知处理。
            val range = connection.getHeaderField("Content-Range") ?: return -1L
            val total = range.substringAfterLast('/', "").takeWhile { it.isDigit() }
            return if (total.isEmpty()) -1L else total.toLong()
        }
        val len = connection.contentLengthLong
        return if (len >= 0) len + resumeFrom else -1L
    }

    private fun mb(bytes: Long) = bytes / 1024 / 1024

}

/**
 * 供 JVM 单测用的打开器工厂：把 URL 映射到预置的假连接。
 * Android 侧不需要它——默认参数就是真 `URL.openConnection`。
 */
internal typealias ConnectionOpener = (String) -> HttpURLConnection
