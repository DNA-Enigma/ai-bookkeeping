package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.BuildConfig
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
     * **部署级主令牌**（服务端 `DISPATCHER_AUTH_TOKEN`）。
     *
     * 与用户登陆令牌（[dev.dzsun.bookkeeping.core.platform.TokenStore] 里那枚）是
     * 两套东西：这一个是部署方自己的凭据，保护的是"谁能调调度层 API"；
     * 后者保护的是"谁能登陆这个 App"。`/v1` 下的端点目前**只认这一枚**（或它在
     * `POST /v1/tokens` 下签发的子令牌），用户 JWT 会被判 401 —— 见
     * [DispatcherClient.authorization] 里的取值顺序说明。
     */
    @Volatile
    var bearerToken: String? = null

    /** 本次请求的调用方标识，会写进 [Identity.userId]。 */
    @Volatile
    var userId: String = DEFAULT_USER_ID

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        /**
         * 默认地址**按构建类型分流**，与 `res/xml/network_security_config.xml`
         * 的 debug/main 两份配置是同一套切法（那份也只对 debug 放行明文 HTTP）。
         * 两处必须同时成立：地址指对了但明文被拦，症状是"连不上"，
         * 而排查的人会去查地址——把这一致性写进代码，省掉那一轮。
         */
        val DEFAULT_BASE_URL: String
            get() = if (BuildConfig.DEBUG) {
                /** 10.0.2.2 是模拟器访问**宿主机**的专用别名，真机上指向不存在的地方。 */
                "http://10.0.2.2:8010"
            } else {
                /**
                 * Cloudflared 隧道公网地址，手机不限网络直接连。
                 * 隧道重启 URL 会变，到时重新打包或在设置页改。
                 */
                "https://prospective-sequences-str-lamp.trycloudflare.com"
            }

        /** 当前是「自己和少数朋友」用，账号隔离最简即可。 */
        const val DEFAULT_USER_ID = "local"
    }
}
