package dev.dzsun.bookkeeping.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 下载下来的 APK 没通过校验时抛这个。**不要装上去**——那等于跳过唯一一道防线。 */
class ApkVerificationException(expected: String, actual: String) :
    IOException("APK 校验失败：期望 sha256 $expected，实际 $actual")

/**
 * 下载新版 APK 并交给系统安装器。
 *
 * 用 `ACTION_VIEW` + `FileProvider` 而不是 `PackageInstaller` 会话 API：
 * 后者要自己管会话与结果回调，收益只是能静默装——而侧载本来就该让用户点一下确认。
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 下载并校验。已下过且校验通过的文件直接复用，不重复下载。
     *
     * [onProgress] 的第二个参数在服务端没给 `sizeBytes` 时是 -1。
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val target = File(directory, "update-${manifest.versionCode}.apk")
        val total = manifest.sizeBytes ?: -1L

        // 上一次下载成功过的就直接用——用户重试不该再花一次流量
        if (target.isFile && target.sha256OrNull() == manifest.sha256.lowercase()) {
            onProgress(target.length(), total)
            return@withContext target
        }

        // 换版本时把旧的清掉，免得缓存目录越堆越大
        directory.listFiles()?.forEach { if (it != target) it.delete() }

        val connection = URL(manifest.url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("下载 APK 失败：HTTP $status")

            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                // 装一个校验不过的 APK 比不装更糟：要么装不上，要么装上被掉过包的东西
                target.delete()
                throw ApkVerificationException(manifest.sha256.lowercase(), actual)
            }
            target
        } finally {
            connection.disconnect()
        }
    }

    /** 交给系统安装器。用户会看到标准安装确认页。 */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, authority(), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 是否已获准安装未知来源应用。
     *
     * Android 8 起这是**每个应用单独授权**的（不再是一个全局开关），
     * 没授权时 `install()` 会静默失败，所以调用方应先查这个并引导用户去设置。
     */
    fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun authority(): String = "${context.packageName}.fileprovider"

    private fun File.sha256OrNull(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val CACHE_SUBDIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
