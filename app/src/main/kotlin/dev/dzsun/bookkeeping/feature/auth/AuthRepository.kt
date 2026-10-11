package dev.dzsun.bookkeeping.feature.auth

import dev.dzsun.bookkeeping.core.network.AuthApi
import dev.dzsun.bookkeeping.core.network.DispatcherException
import dev.dzsun.bookkeeping.core.network.UserFacingErrors
import dev.dzsun.bookkeeping.core.platform.TokenStore
import javax.inject.Inject

/**
 * 会话出口。**服务端实现**：邮箱验证码/密码登录，会话是服务端签的 JWT
 * （存 [TokenStore]，7 天有效）。2026-10-11 由本地账号（[LocalAuthRepository]，
 * 还在本文件历史里）切换到服务端——本地那套只做纯本机验证，多设备不同步，
 * 且验证码根本发不出去。
 *
 * 错误映射统一走 [UserFacingErrors.authMessageFor]：服务端 `detail` 会回声
 * 用户输入（实测"邮箱格式不正确：'bad'"），按项目口径不进界面。
 */
interface AuthRepository {
    /** 当前会话是否可用（JWT 没过期）。**离线可判**——看过期时刻，不问服务端。 */
    suspend fun isLoggedIn(): Boolean

    /** 发验证码。返回服务端给的重发冷却秒数（界面倒计时用它，不写死 60）。 */
    suspend fun sendCode(email: String): SendCodeOutcome

    /** 验证码登录。**未注册会顺手注册**——服务端对"没注册"与"码不对"回同一句话
     *  （防用户枚举），客户端分辨不出，只能先试登录、401 再试注册。 */
    suspend fun signInWithCode(email: String, code: String): AuthResult

    /** 密码登录。 */
    suspend fun signIn(email: String, password: String): AuthResult

    /** 注册（验证码 + 可选密码）。注册成功即登录，服务端直接签发令牌。 */
    suspend fun register(email: String, code: String, password: String?): AuthResult

    suspend fun signOut()
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failed(val message: String) : AuthResult
}

sealed interface SendCodeOutcome {
    /** 已发出。[resendAfterSeconds] 后才允许再要一枚。 */
    data class Sent(val resendAfterSeconds: Int) : SendCodeOutcome
    data class Failed(val message: String) : SendCodeOutcome
}

class ServerAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
) : AuthRepository {

    override suspend fun isLoggedIn(): Boolean =
        tokenStore.hasUsableToken(System.currentTimeMillis() / 1000)

    override suspend fun sendCode(email: String): SendCodeOutcome = try {
        val ack = api.sendCode(email)
        SendCodeOutcome.Sent(ack.resendAfterSeconds)
    } catch (e: Exception) {
        SendCodeOutcome.Failed(UserFacingErrors.authMessageFor(e))
    }

    override suspend fun signInWithCode(email: String, code: String): AuthResult = try {
        save(api.loginWithCode(email, code))
        AuthResult.Success
    } catch (e: Exception) {
        // 401 = 没注册**或**码不对（服务端同一句话，防枚举）。先试注册：
        // 注册成功即登录（服务端 register 直接签发令牌）；注册也 401 就是码真不对。
        if (isUnauthorized(e)) {
            try {
                save(api.register(email, code, password = null))
                AuthResult.Success
            } catch (e2: Exception) {
                AuthResult.Failed(UserFacingErrors.authMessageFor(e2))
            }
        } else {
            AuthResult.Failed(UserFacingErrors.authMessageFor(e))
        }
    }

    override suspend fun signIn(email: String, password: String): AuthResult = try {
        save(api.loginWithPassword(email, password))
        AuthResult.Success
    } catch (e: Exception) {
        AuthResult.Failed(UserFacingErrors.authMessageFor(e))
    }

    override suspend fun register(email: String, code: String, password: String?): AuthResult = try {
        save(api.register(email, code, password))
        AuthResult.Success
    } catch (e: Exception) {
        AuthResult.Failed(UserFacingErrors.authMessageFor(e))
    }

    override suspend fun signOut() {
        tokenStore.clear()
    }

    private fun save(session: dev.dzsun.bookkeeping.core.network.AuthSession) {
        tokenStore.save(
            token = session.token,
            expiresAtSeconds = session.expiresAtSeconds,
            email = session.user.email,
        )
    }

    private fun isUnauthorized(e: Exception): Boolean =
        e is DispatcherException && e.code == dev.dzsun.bookkeeping.core.network.Problem.CODE_UNAUTHORIZED
}
