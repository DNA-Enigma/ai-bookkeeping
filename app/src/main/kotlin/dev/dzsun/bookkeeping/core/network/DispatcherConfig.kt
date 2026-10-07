package dev.dzsun.bookkeeping.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调度层的接入点。**运行时可改**，因为开发期指向本地跑的 `smart-dispatcher`，
 * 正式环境要换地址；写死在代码里会让每次切换都变成一次发版。
 *
 * 后续应由设置页读写入（契约的 `/v1/health` 可用于连通性自检）。
 */
@Singleton
class DispatcherConfig @Inject constructor() {

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL

    /**
     * Bearer token。
     *
     * **契约里没有任何获取/续期 token 的端点**（只有全局 `bearerAuth: JWT` 声明），
     * 这是需要和后端同学对齐的一个缺口。在拿到发放方式之前，这里手工注入。
     */
    @Volatile
    var bearerToken: String? = null

    /** 本次请求的调用方标识，会写进 [Identity.userId]。 */
    @Volatile
    var userId: String = DEFAULT_USER_ID

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /**
         * Cloudflared 隧道公网地址，手机不限网络直接连。
         * 隧道重启 URL 会变，到时重新打包或在设置页改。
         */
        const val DEFAULT_BASE_URL = "https://loops-mines-zip-wagner.trycloudflare.com"

        /** 当前是「自己和少数朋友」用，账号隔离最简即可。 */
        const val DEFAULT_USER_ID = "local"
    }
}
