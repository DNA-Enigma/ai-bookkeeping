package dev.dzsun.bookkeeping.feature.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地会话存储接口（旧的本地账号机制用，2026-10-11 起已被
 * [dev.dzsun.bookkeeping.core.platform.TokenStore] + [ServerAuthRepository] 取代）。
 * 留着只为编译兼容与旧数据清理，新代码不要引用。
 */
interface SessionStore {
    fun email(): String?
    fun setEmail(email: String?)

    /** 不可逆的口令校验值（[PasswordVerifier.hash] 的产物），**不是口令**。 */
    fun passwordHash(): String?
    fun saveAccount(email: String, passwordHash: String)
    fun clearSession()
}

@Singleton
class SharedPrefsSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) : SessionStore {

    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)

    init {
        // 旧版本把**明文口令**写在 "password" 这个键里。升级后立刻抹掉——
        // 只改写入路径、不清旧值的话，那份明文会一直躺在磁盘上。
        if (prefs.contains(KEY_LEGACY_PLAINTEXT_PASSWORD)) {
            prefs.edit().remove(KEY_LEGACY_PLAINTEXT_PASSWORD).apply()
        }
    }

    override fun email(): String? = prefs.getString(KEY_EMAIL, null)

    override fun setEmail(email: String?) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    override fun passwordHash(): String? = prefs.getString(KEY_PASSWORD_HASH, null)

    override fun saveAccount(email: String, passwordHash: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD_HASH, passwordHash)
            .remove(KEY_LEGACY_PLAINTEXT_PASSWORD)
            .apply()
    }

    override fun clearSession() {
        prefs.edit().remove(KEY_EMAIL).remove(KEY_PASSWORD_HASH).apply()
    }

    private companion object {
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD_HASH = "password_hash"

        /** 旧版的明文口令键，仅用于清除，不再有写入点。 */
        const val KEY_LEGACY_PLAINTEXT_PASSWORD = "password"
    }
}
