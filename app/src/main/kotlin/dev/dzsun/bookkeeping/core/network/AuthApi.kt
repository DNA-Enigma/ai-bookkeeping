package dev.dzsun.bookkeeping.core.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy

/**
 * 账号端点（`/auth` 下这四个）：发验证码、验验证码、注册、登陆。
 *
 * 与 [DispatcherClient] 同一套 HTTP 风格（[HttpURLConnection]，不引新依赖），
 * 但**刻意分成两个类**：那一份的超时值是按任务预算定的（读超时 180 秒，
 * 因为一次票据任务实测跑过 81 秒），而账号端点是**交互式**的——用户点一下
 * 就在那儿等着。两者共用一份超时配置，必然有一边是错的。
 *
 * 四个端点在服务端都是**免鉴权**的（见后端 `PUBLIC_PATHS`），所以这里不发
 * `Authorization`：登陆请求带着上一枚（可能已失效的）令牌去换新的，
 * 除了让日志更难看之外没有任何作用。
 *
 * 失败一律抛 [DispatcherException]，由调用方按 `code` 映射成用户文案
 * （见 [dev.dzsun.bookkeeping.feature.auth.AuthMessages]）——**服务端的
 * `detail` 是给人看的，但不保证不回声输入**（实测"邮箱格式不正确：'bad'"
 * 会把原样输入带回来），按本项目既有口径不进界面。
 */
@Singleton
class AuthApi @Inject constructor(
    private val config: DispatcherConfig,
) {

    /**
     * 发一枚 6 位验证码到邮箱。
     *
     * 响应里**没有验证码**（服务端刻意如此），拿到的是两个时间：
     * 这枚码能活多久、以及多久之后才能再要一枚。倒计时以服务端给的
     * [SendCodeAck.resendAfterSeconds] 为准而不是写死 60 —— 服务端与客户端
     * 各写一个数，就会出现"倒计时走完却依然 429"的假失败。
     */
    suspend fun sendCode(email: String): SendCodeAck =
        post("/auth/send-code", encode(EmailBody.serializer(), EmailBody(email)), SendCodeAck.serializer())

    /** 只校验、**不作废**这枚码——注册是"先验码、再设密码"两步，第一步就烧掉的话第二步必然失败。 */
    suspend fun verifyCode(email: String, code: String): Boolean =
        post(
            "/auth/verify-code",
            encode(CodeBody.serializer(), CodeBody(email, code)),
            VerifyCodeAck.serializer(),
        ).valid

    /** 注册并直接拿到令牌（服务端注册成功即登陆，不必再走一遍登陆页）。密码可选。 */
    suspend fun register(email: String, code: String, password: String?): AuthSession =
        post(
            "/auth/register",
            encode(RegisterBody.serializer(), RegisterBody(email, code, password)),
            AuthSession.serializer(),
        )

    /**
     * 验证码登陆。
     *
     * 服务端对"这个邮箱没注册过"与"验证码不对"回**同一句话**（防用户枚举），
     * 所以客户端**分辨不出**这两种情况——界面不要替它断言"该邮箱未注册"，
     * 只能提供一个"还没账号？去注册"的出口。见 `LoginScreen`。
     */
    suspend fun loginWithCode(email: String, code: String): AuthSession =
        post(
            "/auth/login",
            encode(LoginBody.serializer(), LoginBody(email, code = code)),
            AuthSession.serializer(),
        )

    /** 密码登陆。与验证码登陆共用同一个端点，服务端二选一。 */
    suspend fun loginWithPassword(email: String, password: String): AuthSession =
        post(
            "/auth/login",
            encode(LoginBody.serializer(), LoginBody(email, password = password)),
            AuthSession.serializer(),
        )

    // ------------------------------------------------------------ 传输

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        wireJson.encodeToString(serializer, value)

    private suspend fun <T> post(
        path: String,
        bodyJson: String,
        deserializer: DeserializationStrategy<T>,
    ): T = wireJson.decodeFromString(
        deserializer,
        execute(path, bodyJson.encodeToByteArray()).decodeToString(),
    )

    private suspend fun execute(path: String, body: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", CONTENT_TYPE_JSON)
            }
        } catch (e: IOException) {
            // 同 DispatcherClient：原始的「无法连接 <baseUrl><path>」带着服务端地址，
            // 不进界面（见 UserFacingErrors），排查读 cause。
            throw DispatcherException(null, UserFacingErrors.UNREACHABLE, e)
        }
        try {
            connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * 读超时 30 秒，比任务端点（180 秒）短得多，因为这条路上没有模型：
         * 服务端做的是查表、比码、签 JWT，量级是毫秒。唯一慢的一跳是发信，
         * 而后端自己给 SMTP 设了 10 秒超时（`mailer.SMTP_TIMEOUT_S`）——
         * 30 秒是「服务端那条 10 秒超时 + 握手与投递开销」再留一倍余量。
         *
         * 这个值同时也是**用户愿意等多久**：他正盯着一个按钮。
         */
        const val READ_TIMEOUT_MS = 30_000
    }
}

// ------------------------------------------------------------ 线格式

/**
 * `POST /auth/send-code` 的响应。
 *
 * 全部给默认值：契约承诺只做加法，将来服务端加字段不会让我们崩；
 * 反过来若某个字段被拿掉，这里也会退化成 0 而不是抛异常——倒计时退化成
 * "立即可重发"，服务端那边仍有 429 兜着，比整个登陆页打不开好。
 */
@Serializable
data class SendCodeAck(
    val sent: Boolean = true,
    /** 这枚验证码的有效期（秒）。实测 300。 */
    @SerialName("expires_in") val expiresInSeconds: Int = 0,
    /** 距离下次可发送的秒数。实测 60。**界面倒计时用它，不写死。** */
    @SerialName("resend_after") val resendAfterSeconds: Int = 0,
)

@Serializable
private data class VerifyCodeAck(val valid: Boolean = false)

/** 登陆 / 注册成功后的会话。 */
@Serializable
data class AuthSession(
    val token: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresInSeconds: Long = 0,
    /**
     * 到期时刻，**秒**级 Unix 时间（服务端 `int(time.time()) + TTL`）。
     *
     * 不是毫秒。当成毫秒会得到 1970 年，于是每一枚刚拿到的令牌都立刻"已过期"，
     * 表现是"登陆成功但马上又弹回登陆页"——所以 [dev.dzsun.bookkeeping.core.platform.TokenStore]
     * 的入参名字里带 `Seconds`。
     */
    @SerialName("expires_at") val expiresAtSeconds: Long = 0,
    val user: AuthUser = AuthUser(),
)

@Serializable
data class AuthUser(val email: String = "")

@Serializable
private data class EmailBody(val email: String)

@Serializable
private data class CodeBody(val email: String, val code: String)

/** 密码可空：产品上"只用验证码登陆"是合法路径。 */
@Serializable
private data class RegisterBody(val email: String, val code: String, val password: String? = null)

/** 二选一。两个都不给会被服务端 400 挡下（fail-closed），客户端也本地拦一道。 */
@Serializable
private data class LoginBody(
    val email: String,
    val code: String? = null,
    val password: String? = null,
)
