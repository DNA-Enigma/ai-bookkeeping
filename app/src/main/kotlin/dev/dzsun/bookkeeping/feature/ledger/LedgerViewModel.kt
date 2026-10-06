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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class LedgerUiState(
    val monthLabel: String = "",
    val currency: String = "",
    val entries: List<LedgerRow> = emptyList(),
    val monthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val isLoading: Boolean = true,
) {
    val balanceMinor: Long get() = monthIncomeMinor - monthExpenseMinor
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val repository: LedgerRepository,
    clock: Clock,
) : ViewModel() {

    private val today = clock.today()
    private val monthStart = today.withDayOfMonth(1)
    private val monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())
    private val monthLabel = "${today.year} 年 ${today.monthValue} 月"

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
                ) { entries, expense, income ->
                    LedgerUiState(
                        monthLabel = monthLabel,
                        currency = currency,
                        entries = entries,
                        // 支出分类一侧的分录恒为正，收入一侧恒为负，这里统一成正数交给界面决定符号
                        monthExpenseMinor = expense,
                        monthIncomeMinor = -income,
                        isLoading = false,
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
