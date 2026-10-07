package dev.dzsun.bookkeeping.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.platform.Clock
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 半年趋势上的一根柱子。金额为整数最小单位（分），与账本同口径。 */
data class TrendPoint(
    val yearMonth: YearMonth,
    val expenseMinor: Long,
    /** 区分「这个月没记录」与「只有收入没有支出」——趋势空态看的是前者。 */
    val incomeMinor: Long,
)

data class ReportUiState(
    val month: YearMonth,
    /** 当前真正的月份。月份切换不许越过它。 */
    val currentMonth: YearMonth,
    /**
     * null 表示这个月的报表**还算不出来**：币种未就绪，或正在切换月份。
     *
     * 没有单独的 `isLoading` 标志——它能取的值恒等于 `report == null`，
     * 多一个能跟事实脱节的字段，就是多一次"转圈转完了但报表还是空的"的机会。
     */
    val report: MonthlyReport? = null,
    /**
     * 当前账期起往前 6 个月（含当前账期）的支出，时间正序。
     * 空列表表示**还算不出来**，不是「没有记录」——别拿它画空态。
     */
    val trend: List<TrendPoint> = emptyList(),
) {
    /**
     * 不允许翻到未来的月份。
     *
     * 未来的月份必然没有任何数据，翻过去只会让用户以为账丢了——
     * 一个只能显示"本月还没开始"的页面，不该是一个可到达的状态。
     */
    val canGoNext: Boolean get() = month < currentMonth

    val canGoPrevious: Boolean get() = true
}

/**
 * 月度报表。
 *
 * 三份数据合流：**聚合来自 [MonthlyReportSource]，预算来自 [BudgetStore]，
 * 阈值来自 [BudgetSettings]**。三者任意一个变了都要重算——用户在预算页改完额度
 * 回到报表，标记必须已经是新的，不需要杀进程重进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val source: MonthlyReportSource,
    private val budgetStore: BudgetStore,
    private val budgetSettings: BudgetSettings,
    clock: Clock,
) : ViewModel() {

    /** 固定"今天"，跨月边界（这个月的日均按已过天数算）才不会随刷新漂。 */
    private val today = clock.today()
    private val currentMonth = YearMonth.from(today)

    private val selected = MutableStateFlow(currentMonth)

    private val _state = MutableStateFlow(
        ReportUiState(month = currentMonth, currentMonth = currentMonth),
    )
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            selected
                // flatMapLatest：切月份时把上个月的数据流丢掉，晚到的旧月份结果不会覆盖新的
                .flatMapLatest { month ->
                    combine(
                        source.observeMonth(month),
                        budgetStore.observeBudgets(),
                        budgetSettings.thresholds,
                    ) { aggregate, budgets, thresholds ->
                        month to MonthlyReportCalculator.build(aggregate, budgets, thresholds, today)
                    }
                }
                .collect { (month, report) ->
                    _state.update { it.copy(month = month, report = report) }
                }
        }
        viewModelScope.launch {
            selected
                .flatMapLatest { observeRecentMonths(it) }
                .collect { trend ->
                    _state.update { it.copy(trend = trend) }
                }
        }
    }

    /**
     * 当前账期起往前推 6 个月（含当前账期）的月度支出，按时间正序。
     *
     * 只走 [MonthlyReportSource]——报表不直接认账本仓库。
     */
    private fun observeRecentMonths(month: YearMonth): Flow<List<TrendPoint>> {
        val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }
        return combine(months.map { source.observeMonth(it) }) { aggregates ->
            aggregates.map {
                TrendPoint(
                    yearMonth = it.yearMonth,
                    expenseMinor = it.expenseMinor,
                    incomeMinor = it.incomeMinor,
                )
            }
        }
    }

    fun onPreviousMonth() = select(_state.value.month.minusMonths(1))

    fun onNextMonth() {
        val next = _state.value.month.plusMonths(1)
        if (!next.isAfter(currentMonth)) select(next)
    }

    private fun select(month: YearMonth) {
        if (month == _state.value.month) return
        // 立刻清掉旧报表与旧趋势：留着上个月的数字配着新月份的标题，
        // 是这一屏最容易被误读的东西（"这个月只花了 800"）
        _state.update { it.copy(month = month, report = null, trend = emptyList()) }
        selected.value = month
    }
}
