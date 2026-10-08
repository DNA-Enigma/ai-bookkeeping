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
 * 本地账号：本次安装内持久化（SharedPreferences）。
 *
 * 账号体系还没接到服务端，所以校验是本机的；但**口令明文绝不落盘**——
 * 落盘的是 [PasswordVerifier] 派生的不可逆校验值。见该文件的说明。
 */
class LocalAuthRepository @Inject constructor(
    private val store: SessionStore,
) : AuthRepository {

    override suspend fun isLoggedIn(): Boolean = store.email() != null

    override suspend fun signIn(email: String, password: String): AuthResult {
        val hash = store.passwordHash()
        return if (store.email() == email && PasswordVerifier.verify(password, hash)) {
            AuthResult.Success
        } else {
            AuthResult.Failed("邮箱或密码不对，或者还没注册")
        }
    }

    override suspend fun register(email: String, password: String): AuthResult {
        if (!email.contains("@")) return AuthResult.Failed("邮箱格式不正确")
        if (password.length < 6) return AuthResult.Failed("密码至少 6 位")
        // 只落不可逆校验值；口令本身用完即弃
        store.saveAccount(email, PasswordVerifier.hash(password))
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

    /** 不可逆的口令校验值（[PasswordVerifier.hash] 的产物），**不是口令**。 */
    fun passwordHash(): String?
    fun saveAccount(email: String, passwordHash: String)
    fun clearSession()
}
