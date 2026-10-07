package dev.dzsun.bookkeeping.feature.report

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.ledger.CategoryBudget
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分类月度预算的存取口。
 *
 * 报表页、预算页、记账后的提醒都只认这个接口，不直接依赖 [LedgerRepository]——
 * 换数据来源时只动 `ReportModule` 里的一个绑定。
 */
interface BudgetStore {

    /** 全部已设预算。按分类 id 排序，界面的顺序才是稳定的。 */
    fun observeBudgets(): Flow<List<CategoryBudget>>

    /** 写入某分类的预算。**金额非正表示清除**这条预算——一个入口就够，不必开两个。 */
    suspend fun setBudget(categoryId: String, amountMinor: Long)
}

/**
 * 预算落在账本库里（`budget` 表），由 [LedgerRepository] 读写。
 *
 * 此前预算存在 SharedPreferences 里（一个界面侧的 prefs 实现）。搬进库里的理由不是
 * 「表更正式」，而是**预算和账本必须是同一份数据**：同一个迁移、同一次备份、
 * 同一个事务。分开存的时候，用户换机/恢复备份会得到一本账配上另一套预算线，
 * 而这种错配是静默的——数字都还在，只是互相对不上。
 */
@Singleton
class RepositoryBudgetStore @Inject constructor(
    private val repository: LedgerRepository,
) : BudgetStore {

    override fun observeBudgets(): Flow<List<CategoryBudget>> = repository.observeBudgets()

    override suspend fun setBudget(categoryId: String, amountMinor: Long) =
        repository.setBudget(categoryId, amountMinor)
}

/**
 * 预算提醒阈值的**用户设置**，与 `AutoConfirmSettings` 同一个套路：
 * 出厂默认值在 [BudgetThresholds]，用户调过的值存下来且**立刻生效**——
 * 记账页、报表页读的都是同一个源，不需要重启或重进页面。
 *
 * 阈值**刻意留在 prefs 而不是账本表里**：它是个人偏好（我提醒得早还是晚），
 * 不是账本事实。预算额度进库是因为它要和账本一起备份；阈值进库只会让
 * 「换个提醒线」变成一次数据库迁移。
 */
interface BudgetSettings {
    val thresholds: StateFlow<BudgetThresholds>
    fun setNearRatio(ratio: Float)
    fun setOverRatio(ratio: Float)
}

@Singleton
class PrefsBudgetSettings @Inject constructor(
    @ApplicationContext context: Context,
) : BudgetSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 读进来的值先过一遍 clamp：prefs 里可能躺着旧版本写下的、或手工改过的值，
    // 两条线交叉的阈值会让判定自相矛盾
    private val _thresholds = MutableStateFlow(
        BudgetGate.clamp(
            BudgetThresholds(
                nearRatio = prefs.getFloat(KEY_NEAR, BudgetGate.DEFAULT_NEAR_RATIO),
                overRatio = prefs.getFloat(KEY_OVER, BudgetGate.DEFAULT_OVER_RATIO),
            ),
        ),
    )

    override val thresholds: StateFlow<BudgetThresholds> = _thresholds.asStateFlow()

    override fun setNearRatio(ratio: Float) {
        val current = _thresholds.value
        update(current.copy(nearRatio = BudgetGate.clampNear(ratio, current.overRatio)))
    }

    override fun setOverRatio(ratio: Float) {
        val current = _thresholds.value
        update(current.copy(overRatio = BudgetGate.clampOver(ratio, current.nearRatio)))
    }

    private fun update(next: BudgetThresholds) {
        prefs.edit()
            .putFloat(KEY_NEAR, next.nearRatio)
            .putFloat(KEY_OVER, next.overRatio)
            .apply()
        _thresholds.value = next
    }

    private companion object {
        const val PREFS_NAME = "budget_settings"
        const val KEY_NEAR = "near_ratio"
        const val KEY_OVER = "over_ratio"
    }
}
