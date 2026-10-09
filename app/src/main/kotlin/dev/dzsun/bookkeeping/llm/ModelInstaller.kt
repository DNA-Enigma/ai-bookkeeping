package dev.dzsun.bookkeeping.llm

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 内置模型资产的读取口。Android 侧包 `context.assets`，测试用内存假件。
 *
 * 为什么隔这一层：安装模型的逻辑（找文件、校验大小、写临时文件、原子改名、
 * 空间不足、中断清理）必须有单测钉着，而 `AssetManager` 在 JVM 单测里根本不存在。
 */
interface ModelAssets {
    /** 资产路径列表，形如 `models/Spark-X2.5-1.7B-Q4_K_M.gguf` */
    fun names(): List<String>

    /** 资产字节数；未知时返回 -1（会退化成不校验大小） */
    fun sizeOf(name: String): Long

    fun open(name: String): InputStream
}

/**
 * 把打包进 APK 的 GGUF 释放到应用私有目录，让「装上 App 就能加载模型」成立。
 *
 * 三条口径：
 * 1. **先写 `.part`，校验字节数后才改名。** 半截文件绝不叫正式文件名——
 *    否则下次进来 `exists()` 为真却是个残缺模型，llama 加载时才炸。
 * 2. **已存在且字节数相符就跳过**，不重复写 1 GB。
 * 3. **任何失败都把 `.part` 清掉**，宁可下次重来，不留垃圾占着 1 GB 空间。
 */
class ModelInstaller(
    private val assets: ModelAssets,
    private val targetDir: File,
    private val freeSpace: () -> Long = { targetDir.usableSpace },
) {

    sealed interface Result {
        /** 本次真的写进去了 */
        data class Installed(val file: File, val bytes: Long) : Result

        /** 目标处已有完整模型，直接可用 */
        data class AlreadyPresent(val file: File) : Result

        data class Failed(val message: String) : Result
    }

    /** 内置模型的字节数；没有内置模型时返回 -1（UI 用它显示总进度） */
    fun expectedBytes(): Long {
        val name = assets.names().firstOrNull { it.endsWith(".gguf", ignoreCase = true) } ?: return -1L
        return assets.sizeOf(name)
    }

    /**
     * @param onProgress 0.0..1.0，节流到约每 1% 或每 8 MB 上报一次
     */
    fun ensure(onProgress: (Float) -> Unit = {}): Result {
        val assetName = assets.names().firstOrNull { it.endsWith(".gguf", ignoreCase = true) }
            ?: return Result.Failed("安装包里没有内置模型资产")

        val fileName = assetName.substringAfterLast('/')
        val expected = assets.sizeOf(assetName)
        val target = File(targetDir, fileName)

        if (target.isFile && expected > 0 && target.length() == expected) {
            return Result.AlreadyPresent(target)
        }

        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return Result.Failed("建不了模型目录：${targetDir.absolutePath}")
        }
        if (expected > 0 && freeSpace() < expected + SPACE_MARGIN_BYTES) {
            return Result.Failed(
                "空间不足：模型 ${mb(expected)} MB，可用 ${mb(freeSpace())} MB，" +
                    "先清理一点空间再试"
            )
        }

        // 上次中断留下的残骸，先清掉
        val part = File(targetDir, "$fileName.part")
        if (part.exists() && !part.delete()) {
            return Result.Failed("清不掉上次的残留文件：${part.name}")
        }

        val tmp = assets.open(assetName)
        var written = 0L
        var lastReported = -1
        return try {
            tmp.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written - lastReported >= REPORT_EVERY_BYTES) {
                            lastReported = written.toInt()
                            if (expected > 0) onProgress((written.toFloat() / expected).coerceAtMost(1f))
                        }
                    }
                }
            }
            if (expected > 0 && written != expected) {
                part.delete()
                return Result.Failed("模型没写全：$written / $expected 字节")
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return Result.Failed("覆盖不掉旧模型文件：${target.name}")
            }
            if (!part.renameTo(target)) {
                part.delete()
                return Result.Failed("改名失败：${part.name} -> ${target.name}")
            }
            onProgress(1f)
            Result.Installed(target, written)
        } catch (e: IOException) {
            part.delete()
            Result.Failed("写模型失败：${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            part.delete()
            Result.Failed("写模型被拒：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** 写入缓冲 512 KB：1 GB 约 2000 次 read，开销可忽略 */
        private const val BUFFER_BYTES = 512 * 1024

        /** 进度节流：8 MB 或约 1% 上报一次，别让 UI 每 512 KB 重组一次 */
        private const val REPORT_EVERY_BYTES = 8L * 1024 * 1024

        /** 余量：文件系统元数据 + 避免把盘写到 100% */
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        private fun mb(bytes: Long) = bytes / 1024 / 1024
    }
}
