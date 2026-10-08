package dev.dzsun.bookkeeping.feature.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
