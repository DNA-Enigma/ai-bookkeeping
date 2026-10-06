package dev.dzsun.bookkeeping.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatsPeriod(val label: String, val months: Int) {
    MONTH("本月", 1),
    QUARTER("近三月", 3),
    YEAR("今年", 12),
}

data class CategorySlice(
    val categoryId: String,
    val name: String,
    val amountMinor: Long,
    val ratio: Float,
)

data class MonthBar(
    val yearMonth: YearMonth,
    val expenseMinor: Long,
    val incomeMinor: Long,
)

data class AiInsight(
    val title: String,
    val body: String,
)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val currency: String = "CNY",
    val expenseMinor: Long = 0,
    val incomeMinor: Long = 0,
    val balanceMinor: Long = 0,
    val expenseByCategory: List<CategorySlice> = emptyList(),
    val monthlyBars: List<MonthBar> = emptyList(),
    val insights: List<AiInsight> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var allRows: List<LedgerRow> = emptyList()
    private var baseCurrency: String = "CNY"

    init {
        viewModelScope.launch {
            val currency = repository.observeBaseCurrency().first().orEmpty().ifBlank { "CNY" }
            baseCurrency = currency
            combine(repository.observeEntries(), _state) { rows, s -> rows to s.period }
                .collect { (rows, period) ->
                    allRows = rows
                    recompute(period)
                }
        }
    }

    fun onPeriodChange(period: StatsPeriod) {
        _state.update { it.copy(period = period) }
        recompute(period)
    }

    private fun recompute(period: StatsPeriod) {
        val today = LocalDate.now()
        val thisMonth = YearMonth.from(today)
        val startMonth = thisMonth.minusMonths(period.months.toLong() - 1)
        val startDay = startMonth.atDay(1).toEpochDay()
        val endDay = thisMonth.atEndOfMonth().toEpochDay()

        val inRange = allRows.filter { it.dateEpochDay in startDay..endDay }

        val expenseRows = inRange.filter { it.categoryType == AccountType.EXPENSE }
        val incomeRows = inRange.filter { it.categoryType == AccountType.INCOME }

        val expenseTotal = expenseRows.sumOf { it.amountMinor }
        val incomeTotal = incomeRows.sumOf { it.amountMinor }

        val byCategory = expenseRows
            .groupBy { it.categoryId }
            .map { (id, rows) ->
                val sum = rows.sumOf { it.amountMinor }
                CategorySlice(
                    categoryId = id,
                    name = rows.first().categoryName,
                    amountMinor = sum,
                    ratio = if (expenseTotal == 0L) 0f else sum.toFloat() / expenseTotal,
                )
            }
            .sortedByDescending { it.amountMinor }

        // 近 6 个月柱状（不足 6 个月则有多少画多少）
        val bars = (5 downTo 0).map { offset ->
            val ym = thisMonth.minusMonths(offset.toLong())
            val from = ym.atDay(1).toEpochDay()
            val to = ym.atEndOfMonth().toEpochDay()
            val rows = allRows.filter { it.dateEpochDay in from..to }
            MonthBar(
                yearMonth = ym,
                expenseMinor = rows.filter { it.categoryType == AccountType.EXPENSE }.sumOf { it.amountMinor },
                incomeMinor = rows.filter { it.categoryType == AccountType.INCOME }.sumOf { it.amountMinor },
            )
        }

        val insights = buildInsights(byCategory, expenseTotal, incomeTotal, bars, period)

        _state.update {
            it.copy(
                currency = baseCurrency,
                expenseMinor = expenseTotal,
                incomeMinor = incomeTotal,
                balanceMinor = incomeTotal - expenseTotal,
                expenseByCategory = byCategory,
                monthlyBars = bars,
                insights = insights,
                isLoading = false,
            )
        }
    }

    private fun buildInsights(
        byCategory: List<CategorySlice>,
        expenseTotal: Long,
        incomeTotal: Long,
        bars: List<MonthBar>,
        period: StatsPeriod,
    ): List<AiInsight> {
        if (expenseTotal == 0L && incomeTotal == 0L) {
            return listOf(
                AiInsight("还没有数据", "记几笔账后，这里会生成你的消费洞察。"),
            )
        }

        val result = mutableListOf<AiInsight>()

        val top = byCategory.firstOrNull()
        if (top != null && top.ratio > 0) {
            result += AiInsight(
                title = "最大开销：${top.name}",
                body = "占本期支出的 ${(top.ratio * 100).toInt()}%。" +
                    if (top.ratio >= 0.4f) " 占比偏高，可以看看有没有优化空间。"
                    else " 结构还算均衡。",
            )
        }

        if (incomeTotal > 0 && expenseTotal > 0) {
            val rate = (incomeTotal - expenseTotal).toFloat() / incomeTotal
            result += AiInsight(
                title = if (rate >= 0.2f) "结余不错" else "支出偏紧",
                body = "本期结余率 ${(rate * 100).toInt()}%。" +
                    if (rate >= 0.2f) " 保持这个节奏，储蓄会稳步增长。"
                    else " 建议给非必要支出设个月度上限。",
            )
        }

        val last = bars.lastOrNull()
        val prev = bars.getOrNull(bars.size - 2)
        if (last != null && prev != null && prev.expenseMinor > 0) {
            val change = (last.expenseMinor - prev.expenseMinor).toFloat() / prev.expenseMinor
            val pct = (change * 100).toInt()
            result += AiInsight(
                title = "环比变化",
                body = if (change > 0) {
                    "本月支出比上月增加 $pct%。留意一下大额支出是不是偶发。"
                } else {
                    "本月支出比上月减少 ${-pct}%。控制得不错。"
                },
            )
        }

        if (result.isEmpty()) {
            result += AiInsight("继续记录", "记录越完整，分析越准。")
        }
        return result
    }
}
