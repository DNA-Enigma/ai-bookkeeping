package dev.dzsun.bookkeeping.core.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 更新服务的接入点。**运行时可改**——自托管服务换地址不该要发版。
 */
@Singleton
class UpdateConfig @Inject constructor() {

    /**
     * 版本清单地址。默认指向 **GitHub Releases**（`tools/release-apk.sh` 发布的那个）。
     *
     * 为什么不是以前的 `http://10.0.2.2:8080/version.json`（2026-10-07 改）：
     * ① `10.0.2.2` 是**模拟器访问宿主机的别名**，真机上指向不存在的地方；
     * ② release 构建的 `network_security_config.xml` 是
     *    `cleartextTrafficPermitted="false"`，**明文 HTTP 会被系统直接拦**，必须 https；
     * ③ `releases/latest/download/<资产名>` 这条 URL **永远指向最新一版**，
     *    所以客户端出厂写死这一次就够了，以后每次发版都不用再改客户端。
     *
     * 发版走 `tools/release-apk.sh`（打包 → version.json → `gh release create`）。
     * 本地调试仍可用 `tools/serve-apk.sh`，在这里覆盖 [manifestUrl] 即可。
     */
    @Volatile
    var manifestUrl: String = DEFAULT_MANIFEST_URL

    val isConfigured: Boolean get() = manifestUrl.isNotBlank()

    companion object {
        const val DEFAULT_MANIFEST_URL =
            "https://github.com/DNA-Enigma/ai-bookkeeping/releases/latest/download/version.json"
    }
}

/**
 * 查一次有没有新版。
 *
 * 失败一律静默返回 [UpdateStatus.Failed]：更新检查是后台行为，
 * 网络不通不该弹东西打扰用户——他们本来也没在等这个结果。
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val config: UpdateConfig,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersionCode: Int, currentVersionName: String): UpdateStatus {
        if (!config.isConfigured) return UpdateStatus.Failed("未配置更新服务地址")

        val manifest = try {
            fetchManifest()
        } catch (e: IOException) {
            return UpdateStatus.Failed("连不上更新服务：${e.message}")
        } catch (e: IllegalArgumentException) {
            return UpdateStatus.Failed("版本清单格式不对：${e.message}")
        }

        return if (isNewerVersion(manifest.versionCode, currentVersionCode)) {
            UpdateStatus.Available(manifest, currentVersionCode, currentVersionName)
        } else {
            UpdateStatus.UpToDate(currentVersionName)
        }
    }

    private suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val connection = URL(config.manifestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // 清单是每次都要新的东西，别让中间层缓存住
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            val body = connection.inputStream.readBytes().decodeToString()
            json.decodeFromString<UpdateManifest>(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
