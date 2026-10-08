package dev.dzsun.bookkeeping.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.designsystem.AgentPersona
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置页「AI 助手」区块里用户可选的理财平台。界面文案与这里一一对应。 */
val AGENT_PLATFORMS = listOf("余额宝", "零钱通", "朝朝宝")

/**
 * AI 助手偏好（人设 + 首选理财平台）的存取口。
 *
 * 存 SharedPreferences，与 [dev.dzsun.bookkeeping.feature.ledger.MonthlyBudgetStore]
 * 同一套路。原实现是 SettingsScreen 里的两个 `remember`：**离开页面即丢**——
 * 用户选了「专业顾问」，退出再进来又变回默认。落盘后不再是摆设。
 */
@Singleton
class AgentPrefsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _persona = MutableStateFlow(readPersona())
    val persona: StateFlow<AgentPersona> = _persona.asStateFlow()

    private val _platform = MutableStateFlow(
        prefs.getString(KEY_PLATFORM, AGENT_PLATFORMS.first()) ?: AGENT_PLATFORMS.first(),
    )
    val platform: StateFlow<String> = _platform.asStateFlow()

    fun setPersona(persona: AgentPersona) {
        prefs.edit().putString(KEY_PERSONA, persona.name).apply()
        _persona.value = persona
    }

    fun setPlatform(platform: String) {
        // 只接受已知平台，避免历史键值或空串把界面顶到不存在的一档。
        if (platform !in AGENT_PLATFORMS) return
        prefs.edit().putString(KEY_PLATFORM, platform).apply()
        _platform.value = platform
    }

    private fun readPersona(): AgentPersona {
        val name = prefs.getString(KEY_PERSONA, null) ?: return DEFAULT_PERSONA
        return AgentPersona.entries.firstOrNull { it.name == name } ?: DEFAULT_PERSONA
    }

    private companion object {
        const val PREFS_NAME = "agent_prefs"
        const val KEY_PERSONA = "persona"
        const val KEY_PLATFORM = "platform"
        val DEFAULT_PERSONA = AgentPersona.BUTLER
    }
}
