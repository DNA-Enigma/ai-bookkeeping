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

    override fun email(): String? = prefs.getString(KEY_EMAIL, null)

    override fun setEmail(email: String?) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    override fun credentials(): Pair<String, String>? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return email to password
    }

    override fun saveCredentials(email: String, password: String) {
        prefs.edit().putString(KEY_EMAIL, email).putString(KEY_PASSWORD, password).apply()
    }

    override fun clearSession() {
        prefs.edit().remove(KEY_EMAIL).remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
