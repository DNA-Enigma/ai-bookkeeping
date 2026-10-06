package dev.dzsun.bookkeeping.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.platform.Clock
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 一行分类预算：额度 + 这个月已经花了多少。 */
data class BudgetRow(
    val categoryId: String,
    val name: String,
    val budgetMinor: Long,
    val spentMinor: Long,
    val level: BudgetLevel,
    val usage: Float,
    val overspendMinor: Long,
)

data class BudgetUiState(
    val month: YearMonth,
    /** null 表示科目表还没读出来——没有币种就连一笔预算的金额都没法显示。 */
    val currency: String? = null,
    val rows: List<BudgetRow> = emptyList(),
    /** 还没设预算的支出分类，给「添加」的下拉用。 */
    val unsetCategories: List<AccountEntity> = emptyList(),
    val thresholds: BudgetThresholds = BudgetThresholds(),
    val isLoading: Boolean = true,
) {
    val overspentCount: Int get() = rows.count { it.level == BudgetLevel.OVER }

    /** 全部预算之和。一条都没有时是 null——0 元和"没设"是两回事。 */
    val totalBudgetMinor: Long? get() = if (rows.isEmpty()) null else rows.sumOf { it.budgetMinor }
}

/**
 * 预算管理：按分类设月度额度，顺带调提醒阈值。
 *
 * 预算与**当月实际花销**并排显示——只让用户填一个数字、不告诉他这个月已经花到哪了，
 * 那个数字填多少都是拍脑袋。花销来自 SQL 侧的分类聚合，不额外扫全表。
 */
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val budgetStore: BudgetStore,
    private val budgetSettings: BudgetSettings,
    clock: Clock,
) : ViewModel() {

    private val today = clock.today()
    private val month = YearMonth.from(today)

    /** 支出分类。**从科目表读，不在代码里写死**（约定 3：分类是数据不是代码）。 */
    private val categories = MutableStateFlow<List<AccountEntity>>(emptyList())

    private val _state = MutableStateFlow(BudgetUiState(month = month))
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            categories.value = repository.accountsOfTypes(listOf(AccountType.EXPENSE))
        }
        viewModelScope.launch {
            combine(
                budgetStore.observeBudgets(),
                categories,
                repository.observeCategoryTotals(AccountType.EXPENSE, month.atDay(1), month.atEndOfMonth()),
                budgetSettings.thresholds,
                repository.observeBaseCurrency(),
            ) { budgets, categories, totals, thresholds, currency ->
                buildState(budgets, categories, totals, thresholds, currency)
            }.collect { next -> _state.value = next }
        }
    }

    private fun buildState(
        budgets: List<CategoryBudget>,
        categories: List<AccountEntity>,
        totals: List<CategoryTotal>,
        thresholds: BudgetThresholds,
        currency: String?,
    ): BudgetUiState {
        val spentByCategory = totals.associate { it.categoryId to it.amountMinor }
        val nameById = categories.associate { it.id to it.name }

        val rows = budgets.map { budget ->
            val spent = spentByCategory[budget.categoryId] ?: 0L
            BudgetRow(
                categoryId = budget.categoryId,
                // 分类被删掉/归档后预算还在：显示 id 而不是悄悄丢掉这一行——
                // 丢了的话用户在界面上既看不到也删不掉它，只能靠清数据
                name = nameById[budget.categoryId] ?: budget.categoryId,
                budgetMinor = budget.amountMinor,
                spentMinor = spent,
                level = BudgetGate.level(spent, budget.amountMinor, thresholds),
                usage = BudgetGate.usageRatio(spent, budget.amountMinor) ?: 0f,
                overspendMinor = BudgetGate.overspendMinor(spent, budget.amountMinor),
            )
        }.sortedWith(
            // 超支的排最前：这一页是来管钱的，有问题的行不该埋在下面
            compareByDescending<BudgetRow> { it.level.ordinal }.thenByDescending { it.spentMinor },
        )

        val budgeted = budgets.mapTo(mutableSetOf()) { it.categoryId }

        return BudgetUiState(
            month = month,
            currency = currency,
            rows = rows,
            unsetCategories = categories.filterNot { it.id in budgeted },
            thresholds = thresholds,
            isLoading = currency.isNullOrBlank(),
        )
    }

    /** 金额非正表示清除这条预算——与 [BudgetStore.setBudget] 的约定一致。 */
    fun onSetBudget(categoryId: String, amountMinor: Long) {
        if (categoryId.isBlank()) return
        viewModelScope.launch { budgetStore.setBudget(categoryId, amountMinor) }
    }

    fun onClearBudget(categoryId: String) {
        if (categoryId.isBlank()) return
        viewModelScope.launch { budgetStore.setBudget(categoryId, 0L) }
    }

    fun onNearRatioChange(ratio: Float) = budgetSettings.setNearRatio(ratio)

    fun onOverRatioChange(ratio: Float) = budgetSettings.setOverRatio(ratio)
}
