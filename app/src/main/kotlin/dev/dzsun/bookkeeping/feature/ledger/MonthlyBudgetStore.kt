package dev.dzsun.bookkeeping.feature.ledger

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 月度预算总额的存取口。
 *
 * 存 SharedPreferences（键 `budget_monthly_minor`，单位**最小货币单位**，`Long`）。
 * 与 `feature/report` 里按分类的 `BudgetStore` 不是一回事：那边是账本事实（分类预算线，
 * 进库是为了和账目一起备份），这边是「我这个月打算花多少」这一个数——它是个人偏好，
 * 跟自动入账阈值同一性质，跟着 prefs 走即可，没必要多一次库迁移。
 *
 * **0 表示不设预算**（含用户清空输入）。界面拿到 0 就展示引导文案，
 * 不要算「已用 0%」，更不要出现「超支」——没有预算就不该有超支。
 */
@Singleton
class MonthlyBudgetStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _budgetMinor = MutableStateFlow(prefs.getLong(KEY_MONTHLY_MINOR, 0L))

    /** 当前月预算（最小单位）。0 = 用户没设。 */
    val budgetMinor: StateFlow<Long> = _budgetMinor.asStateFlow()

    /** 写入月预算。**非正数一律清成 0（= 不设预算）**，避免负数预算把进度条算出怪东西。 */
    fun setBudgetMinor(minor: Long) {
        val value = if (minor > 0L) minor else 0L
        prefs.edit().putLong(KEY_MONTHLY_MINOR, value).apply()
        _budgetMinor.value = value
    }

    companion object {
        const val PREFS_NAME = "ledger_prefs"
        const val KEY_MONTHLY_MINOR = "budget_monthly_minor"
    }
}

/**
 * 「元」字符串 → 最小单位。**整数运算，不经过浮点**——
 * `1200.5` 元必须是 `120050` 分，`Double` 会把它算成 `120049.999...` 然后截断成 120049。
 *
 * 空串 / 纯空白返回 0（= 不设预算）。认不出的输入返回 null，由界面提示而不是静默改值。
 */
internal fun yuanToMinor(input: String): Long? {
    val s = input.trim().replace(",", "").removePrefix("¥").removePrefix("元").trim()
    if (s.isEmpty()) return 0L
    val parts = s.split(".")
    if (parts.size > 2) return null
    val yuanPart = parts[0].ifEmpty { "0" }
    val fenPart = if (parts.size == 2) parts[1] else ""
    if (!yuanPart.all { it.isDigit() }) return null
    if (fenPart.isNotEmpty() && !fenPart.all { it.isDigit() }) return null
    if (fenPart.length > 2) return null
    val yuan = yuanPart.toLongOrNull() ?: return null
    val fen = when {
        fenPart.isEmpty() -> 0L
        fenPart.length == 1 -> (fenPart[0] - '0') * 10L
        else -> fenPart.toLong()
    }
    // 上限拦一下：溢出比输错更糟，会得到负数预算
    if (yuan > MAX_YUAN) return null
    return yuan * 100L + fen
}

/** 最小单位 → 「元」的输入框初值。整数除法/取模，不格式化成浮点。 */
internal fun minorToYuanInput(minor: Long): String {
    if (minor <= 0L) return ""
    val yuan = minor / 100L
    val fen = minor % 100L
    return if (fen == 0L) "$yuan" else "$yuan.${fen.toString().padStart(2, '0')}"
}

private const val MAX_YUAN = 99_999_999L
