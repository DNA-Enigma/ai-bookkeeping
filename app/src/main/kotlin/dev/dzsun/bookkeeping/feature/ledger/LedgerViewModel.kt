package dev.dzsun.bookkeeping.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.platform.Clock
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class LedgerUiState(
    val monthLabel: String = "",
    /** null 表示科目表还没读出来。币种未知时不能构造 Money，所以这里刻意可空。 */
    val currency: String? = null,
    val entries: List<LedgerRow> = emptyList(),
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val todayExpenseMinor: Long = 0L,
    val isLoading: Boolean = true,
    /**
     * 月预算（最小单位）。**0 = 用户没设**，此时界面展示引导文案而不是 0/100%。
     * 真源是 [MonthlyBudgetStore]（SharedPreferences），由 ViewModel 合成进本状态。
     */
    val budgetMinor: Long = 0L,
) {
    val balanceMinor: Long get() = monthIncomeMinor - monthExpenseMinor

    /** 没设预算时没有「用掉几成」这回事，返回 0；界面应据此走引导态而非进度条。 */
    val budgetUsedFraction: Float
        get() = if (budgetMinor <= 0L) 0f else (monthExpenseMinor.toFloat() / budgetMinor).coerceIn(0f, 1f)

    val budgetRemainingMinor: Long get() = (budgetMinor - monthExpenseMinor).coerceAtLeast(0L)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val budgetStore: MonthlyBudgetStore,
    clock: Clock,
) : ViewModel() {

    private val today = clock.today()
    private val monthStart = today.withDayOfMonth(1)
    private val monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())
    private val monthLabel = "${today.year} 年 ${today.monthValue} 月"

    private val _hideAmounts = MutableStateFlow(false)
    val hideAmounts: StateFlow<Boolean> = _hideAmounts.asStateFlow()

    fun toggleHideAmounts() {
        _hideAmounts.value = !_hideAmounts.value
    }

    val uiState: StateFlow<LedgerUiState> = repository.observeBaseCurrency()
        .flatMapLatest { currency ->
            // 科目表还没建好时没有本位币可用，此时账本必然也是空的。
            if (currency == null) {
                flowOf(LedgerUiState(isLoading = true))
            } else {
                combine(
                    repository.observeEntries(),
                    repository.observeTotal(AccountType.EXPENSE, currency, monthStart, monthEnd),
                    repository.observeTotal(AccountType.INCOME, currency, monthStart, monthEnd),
                    repository.observeTotal(AccountType.EXPENSE, currency, today, today),
                    budgetStore.budgetMinor,
                ) { entries, expense, income, todayExpense, budget ->
                    LedgerUiState(
                        monthLabel = monthLabel,
                        currency = currency,
                        entries = entries,
                        // 支出分类一侧的分录恒为正，收入一侧恒为负，这里统一成正数交给界面决定符号
                        monthExpenseMinor = expense,
                        monthIncomeMinor = -income,
                        todayExpenseMinor = todayExpense,
                        isLoading = false,
                        budgetMinor = budget,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = LedgerUiState(monthLabel = monthLabel),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
