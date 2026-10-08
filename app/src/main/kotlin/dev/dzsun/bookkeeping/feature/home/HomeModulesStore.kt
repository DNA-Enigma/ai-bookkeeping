package dev.dzsun.bookkeeping.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首页各模块的显示开关。 */
data class HomeModules(
    val greeting: Boolean = true,
    val insights: Boolean = true,
    val overview: Boolean = true,
    val transactions: Boolean = true,
    val health: Boolean = true,
)

/** 可切换的模块。[key] 是落盘用的稳定键名，改它会让老用户的设置复位——别改。 */
enum class HomeModule(val key: String) {
    GREETING("greeting"),
    INSIGHTS("insights"),
    OVERVIEW("overview"),
    TRANSACTIONS("transactions"),
    HEALTH("health"),
}

/**
 * 首页模块开关的存取口。
 *
 * 存 SharedPreferences，与 [dev.dzsun.bookkeeping.feature.ledger.MonthlyBudgetStore]
 * 同一套路（个人偏好跟 prefs 走，不进库、不需要迁移）。
 *
 * 原实现是 HomeScreen 里的一个 `object HomeModulesState`：内存里的
 * `mutableStateOf`，设置页改完**重启即复位**。改成单例 + 落盘后，
 * 首页与设置页读的是同一份、且能跨进程存活。
 */
@Singleton
class HomeModulesStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readAll())
    val state: StateFlow<HomeModules> = _state.asStateFlow()

    fun set(module: HomeModule, enabled: Boolean) {
        // 先落盘再发信号：读的人立刻拿到新值，进程被杀也不会退回旧值。
        prefs.edit().putBoolean(module.key, enabled).apply()
        _state.value = _state.value.with(module, enabled)
    }

    private fun readAll() = HomeModules(
        greeting = prefs.getBoolean(HomeModule.GREETING.key, true),
        insights = prefs.getBoolean(HomeModule.INSIGHTS.key, true),
        overview = prefs.getBoolean(HomeModule.OVERVIEW.key, true),
        transactions = prefs.getBoolean(HomeModule.TRANSACTIONS.key, true),
        health = prefs.getBoolean(HomeModule.HEALTH.key, true),
    )

    private fun HomeModules.with(module: HomeModule, enabled: Boolean): HomeModules = when (module) {
        HomeModule.GREETING -> copy(greeting = enabled)
        HomeModule.INSIGHTS -> copy(insights = enabled)
        HomeModule.OVERVIEW -> copy(overview = enabled)
        HomeModule.TRANSACTIONS -> copy(transactions = enabled)
        HomeModule.HEALTH -> copy(health = enabled)
    }

    private companion object {
        const val PREFS_NAME = "home_modules"
    }
}

/** 首页读开关用。设置页读写同一份 [HomeModulesStore]（见 SettingsViewModel）。 */
@HiltViewModel
class HomeModulesViewModel @Inject constructor(
    store: HomeModulesStore,
) : ViewModel() {
    val state: StateFlow<HomeModules> = store.state
}
