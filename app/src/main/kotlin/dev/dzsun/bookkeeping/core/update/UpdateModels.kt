package dev.dzsun.bookkeeping.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 更新服务上的版本清单。
 *
 * 形状由我们这边定（服务端也是自己的），但刻意保持最小：只放客户端**真正需要用来做决定**
 * 的字段。`versionCode` 是唯一可靠的比较依据——`versionName` 是给人看的，可能不是数字。
 */
@Serializable
data class UpdateManifest(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    /** APK 的直链。 */
    val url: String,
    val sha256: String,
    val sizeBytes: Long? = null,
    val releaseNotes: String? = null,
    /** true 表示低于此版本必须升级才能继续用。默认 false。 */
    val mandatory: Boolean = false,
)

/** 检查结果。用密封类型而不是「有没有更新 + 一堆可空字段」，避免出现自相矛盾的组合。 */
sealed interface UpdateStatus {
    /** 已经是最新。 */
    data class UpToDate(val currentVersionName: String) : UpdateStatus

    /** 有新版可用。 */
    data class Available(
        val manifest: UpdateManifest,
        val currentVersionCode: Int,
        val currentVersionName: String,
    ) : UpdateStatus {
        val isMandatory: Boolean get() = manifest.mandatory
    }

    /** 检查失败——网络不通、清单格式不对等。**不打扰用户**，静默即可。 */
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * 纯函数：远端版本是否比本地新。
 *
 * 抽出来是为了能在 JVM 上直接测——版本比较写错的后果很讨厌：
 * 要么永远提示更新，要么永远不提示。
 */
fun isNewerVersion(remoteVersionCode: Int, localVersionCode: Int): Boolean =
    remoteVersionCode > localVersionCode
