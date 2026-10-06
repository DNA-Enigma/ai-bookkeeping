package dev.dzsun.bookkeeping.feature.auth

import javax.inject.Inject

/**
 * 会话出口。真实短信/账号服务就绪前，用 [LocalAuthRepository] 先把界面流程跑通。
 * 换成服务端实现时只动这个接口的绑定，页面不用改。
 */
interface AuthRepository {
    /** 当前是否已登录。 */
    suspend fun isLoggedIn(): Boolean

    suspend fun signIn(email: String, password: String): AuthResult

    suspend fun register(email: String, password: String): AuthResult

    suspend fun sendResetCode(email: String): AuthResult

    suspend fun signOut()
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failed(val message: String) : AuthResult
}

/**
 * 本地账号：内存 + 本次安装内持久化（SharedPreferences）。
 * 密码只做等长校验，**不做真加密**——这是演示级会话，不是安全存储。
 */
class LocalAuthRepository @Inject constructor(
    private val store: SessionStore,
) : AuthRepository {

    override suspend fun isLoggedIn(): Boolean = store.email() != null

    override suspend fun signIn(email: String, password: String): AuthResult {
        val saved = store.credentials()
        return if (saved != null && saved.first == email && saved.second == password) {
            store.setEmail(email)
            AuthResult.Success
        } else {
            AuthResult.Failed("邮箱或密码不对，或者还没注册")
        }
    }

    override suspend fun register(email: String, password: String): AuthResult {
        if (!email.contains("@")) return AuthResult.Failed("邮箱格式不正确")
        if (password.length < 6) return AuthResult.Failed("密码至少 6 位")
        store.saveCredentials(email, password)
        store.setEmail(email)
        return AuthResult.Success
    }

    override suspend fun sendResetCode(email: String): AuthResult {
        // 真实验证码服务未接，本地只做格式校验后放行
        return if (email.contains("@")) {
            AuthResult.Success
        } else {
            AuthResult.Failed("邮箱格式不正确")
        }
    }

    override suspend fun signOut() {
        store.clearSession()
    }
}

interface SessionStore {
    fun email(): String?
    fun setEmail(email: String?)
    fun credentials(): Pair<String, String>?
    fun saveCredentials(email: String, password: String)
    fun clearSession()
}
