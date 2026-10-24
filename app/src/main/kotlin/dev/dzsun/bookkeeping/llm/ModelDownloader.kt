package dev.dzsun.bookkeeping.llm

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 下载任务的**描述与期望值**——UI/管理器都只认它，不认具体 URL 字符串。
 *
 * [expectedSha256] **没有默认值**：没有期望哈希就只能「不校验」，而「不校验」与
 * 「校验通过」在界面上长得一模一样，用户无从分辨。所以让「没给哈希」在类型层面
 * 就写不出来——要么给真值，要么别走这个下载器。
 *
 * [expectedBytes] 是**便宜的前置判据**：大小对不上直接判不符，不必为了算哈希
 * 把 1 GB 读一遍。`-1` 表示未知（只有测试或老调用点会这样传）。
 */
data class ModelDownloadSpec(
    val url: String,
    val fileName: String,
    val expectedSha256: String,
    val expectedBytes: Long = -1L,
) {
    init {
        require(expectedSha256.length == SHA256_HEX_LENGTH && expectedSha256.all { it.isSha256HexDigit() }) {
            "expectedSha256 必须是 $SHA256_HEX_LENGTH 位十六进制；空串或不完整的摘要无法校验，还会被误当成已校验"
        }
        require(expectedBytes >= -1L) { "expectedBytes 只能是 -1（未知）或真实字节数，收到 $expectedBytes" }
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64

        fun Char.isSha256HexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** 下载结果。**半截文件绝不叫正式名**——成功路径之外不把 `.part` 当成品。 */
sealed interface ModelDownloadResult {
    /** 下载完成且 SHA256 校验通过（[ModelDownloadSpec.expectedSha256] 是必填的，不存在「没验也算成功」）。 */
    data class Success(val file: File, val bytes: Long, val sha256: String) : ModelDownloadResult

    /**
     * 校验不匹配。`.part` **保留**，删不删由调用方决定（管理器的策略是删掉重下一次）。
     */
    data class ChecksumMismatch(val expected: String, val actual: String, val bytes: Long) : ModelDownloadResult

    data class Failed(val message: String) : ModelDownloadResult
}

/**
 * 「目标位置已有的那个文件」的判定结论。
 *
 * 存在的理由：**「文件在盘上」不等于「模型可用」**。曾经这里只看
 * `isFile && length() > 0`，于是任何同名的非空文件都能冒充「已就绪」，
 * 卡片上还写着「校验通过」。现在三个分支各自带证据，调用方必须显式处理 [Mismatch]。
 */
sealed interface ExistingModelFile {
    /** 没有文件，或长度为 0（0 字节不构成任何证据，与不存在同义）。 */
    data object Absent : ExistingModelFile

    /** 在盘上，且大小（若给了期望值）与 SHA256 都与 spec 一致。 */
    data class Verified(val file: File, val bytes: Long, val sha256: String) : ExistingModelFile

    /**
     * 在盘上但对不上。[actualSha256] 为 null 表示**没算**——大小先对不上，
     * 省掉了读 1 GB 的功夫。[reason] 是给人看的那句话。
     */
    data class Mismatch(
        val file: File,
        val bytes: Long,
        val actualSha256: String?,
        val reason: String,
    ) : ExistingModelFile
}

/**
 * GGUF 断点续传下载器——**纯 JVM 可测**（连接由 [opener] 注入）。
 *
 * ## 续传协议
 *
 *  - 目标处已有文件 → 先过 [inspectExisting]：大小（若给了期望值）与 SHA256 都对
 *    才算成功且不发一个请求；对不上就删掉它，按全新下载处理；
 *  - 有 `.part` → 带 `Range: bytes=<已下>-` 重开：**206** = 接着写；
 *    **200** = 服务器不支持 Range，弃 `.part` 重头；
 *  - 没有 `.part` → 全新 GET。
 *
 * ## 磁盘纪律
 *
 *  1. 先写 `.part`，SHA256 通过后才改名——残缺模型不许占用正式文件名；
 *  2. SHA256 **边下边算**（流式），不为校验再读一遍 1 GB；
 *  3. `.part` 什么时候清、什么时候留，**按失败的性质分**：
 *     - 网络/IO 中断、取消 → **保留**：那就是续传的全部本钱，清了等于白下；
 *     - 服务器 200 全量重发 → **清掉**：旧 `.part` 对新请求的前缀无意义；
 *     - SHA256 不匹配 → **保留**，由调用方决定（可能要先看看实际哈希再重下）。
 *
 * ## 关于「校验」
 *
 * [ModelDownloadSpec.expectedSha256] 是必填的，[download] 的每条成功路径都会
 * 真的比对过它。**不存在「没给哈希就跳过校验」这种模式**——跳过校验的结果和
 * 校验通过的结果在界面上长得一样，那是骗人的。
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
     * 检查目标位置已有的文件，**不下载**。
     *
     * 管理器在启动前用它决定卡片该显示「已就绪」还是「可以下载」——那个判断必须
     * 与 [download] 用同一套判据，否则会出现「卡片说能用、下载器说不对」的分裂。
     *
     * 判据（按代价从低到高，任一不过就返回 [ExistingModelFile.Mismatch]）：
     *  1. 是普通文件且长度 > 0；
     *  2. 大小 == [ModelDownloadSpec.expectedBytes]（给了期望值时）；
     *  3. SHA256 == [ModelDownloadSpec.expectedSha256]（这条一定要真算，不省）。
     */
    fun inspectExisting(spec: ModelDownloadSpec): ExistingModelFile {
        val target = targetFile(spec)
        if (!target.isFile) return ExistingModelFile.Absent
        val bytes = target.length()
        // 0 字节不构成任何证据：可能是建了文件就断电，和「不存在」处理成一样
        if (bytes <= 0L) return ExistingModelFile.Absent

        if (spec.expectedBytes > 0 && bytes != spec.expectedBytes) {
            // 大小先对不上就别读 1 GB 算哈希了
            return ExistingModelFile.Mismatch(
                file = target,
                bytes = bytes,
                actualSha256 = null,
                reason = "文件大小 $bytes 字节，期望 ${spec.expectedBytes} 字节",
            )
        }

        val actual = sha256OfFile(target)
        val expected = spec.expectedSha256.trim().lowercase()
        if (actual != expected) {
            return ExistingModelFile.Mismatch(
                file = target,
                bytes = bytes,
                actualSha256 = actual,
                reason = "SHA256 不匹配（实际 ${actual.take(16)}…）",
            )
        }
        return ExistingModelFile.Verified(target, bytes, actual)
    }

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

        // 已有文件：先用与管理器同一套判据验一遍，验过才不发请求。
        // 对不上就删掉——继续保留只会让每次启动都白算一遍 1 GB 的哈希。
        when (val existing = inspectExisting(spec)) {
            is ExistingModelFile.Verified ->
                return ModelDownloadResult.Success(existing.file, existing.bytes, existing.sha256)

            is ExistingModelFile.Mismatch -> {
                // 删不掉不算致命：后面写 .part 再 rename 时还会再撞一次，那里有明确报错
                existing.file.delete()
            }

            ExistingModelFile.Absent -> Unit
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
            // 再核一遍「期望字节数」：有些服务器不给 Content-Length（total 为 -1），
            // 上面那条就失效了，而 spec 里若有真值则仍是廉价判据。
            if (spec.expectedBytes > 0 && written != spec.expectedBytes) {
                return ModelDownloadResult.Failed(
                    "下载大小不符：$written 字节，期望 ${spec.expectedBytes} 字节（部分已保留，重试会接着下）",
                )
            }

            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = spec.expectedSha256.trim().lowercase()
            // 无条件比对：expectedSha256 在类型上就保证是 64 位十六进制，
            // 「没给哈希」这个状态写不出来，所以这里没有「跳过校验」的分支。
            if (actualSha != expected) {
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

    /** 流式算整个文件的 SHA256——1 GB 也不进内存。 */
    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(bytes: Long) = bytes / 1024 / 1024

}
