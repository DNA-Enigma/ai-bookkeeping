package dev.dzsun.bookkeeping.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.database.MonthlyTotal
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatsPeriod(val label: String, val months: Int) {
    MONTH("本月", 1),
    QUARTER("近三月", 3),
    YEAR("今年", 12),
}

/**
 * 日均的分母。
 *
 * 卡片的「日均支出」和 AI 小结里的「日均支出」**必须出自同一个数**，
 * 否则同一屏上会出现两个互相矛盾的日均（模型照着 prompt 念，卡片自己除），
 * 而用户没有理由知道该信哪个。
 */
internal const val DAYS_PER_MONTH = 30

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

/**
 * AI 月度小结这一块的状态。
 *
 * 三态而不是「一段文本 + 一个 isLoading 布尔」：骨架屏（[Loading]）与
 * 「已经拿到本地小结」（[Ready] 且 `fromAi == false`）是两回事，
 * 用一个布尔表达不了，而且会让卡片在降级时看起来像还在加载。
 */
sealed interface AiSummaryState {
    /** 正在向调度层要分析，卡片显示骨架屏。 */
    data object Loading : AiSummaryState

    /**
     * 有内容可展示。[fromAi] 为 false 表示这是本地模板降级的结果——
     * 界面**必须**区别对待，否则就是把一段本地拼的句子冒充成模型分析。
     */
    data class Ready(
        val text: String,
        val fromAi: Boolean,
        val confidence: Float?,
    ) : AiSummaryState
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val currency: String = "CNY",
    val expenseMinor: Long = 0,
    val incomeMinor: Long = 0,
    val balanceMinor: Long = 0,
    val expenseByCategory: List<CategorySlice> = emptyList(),
    val monthlyBars: List<MonthBar> = emptyList(),
    val summary: AiSummaryState = AiSummaryState.Loading,
    val isLoading: Boolean = true,
) {
    /** 日均的除数。卡片与 AI 小结共用它，见 [DAYS_PER_MONTH]。 */
    val periodDays: Int get() = period.months * DAYS_PER_MONTH
}

/**
 * 统计只读 SQL 聚合投影（[LedgerRepository.observeCategoryTotals] / [LedgerRepository.observeMonthlyTotals]），
 * 不再把整本账目装进内存再筛。切换周期只是把 `from`/`to` 传上去，
 * 数据流自己会跟着日期区间重查。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val summarySource: AiSummarySource,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var baseCurrency: String = "CNY"

    /**
     * 上一次真正发出去的询价（就是那份 [MonthlySummaryFacts]）。
     *
     * 这是一道**省钱闸**，不是缓存：`combine` 会在每次账本变动时重发，
     * 而账本变动多数不改变聚合数字（改个备注、加一笔明细）。不去重的话，
     * 用户每编辑一次就多一次真实的模型开销。
     */
    private var lastSummaryFacts: MonthlySummaryFacts? = null

    /** 在飞的那次请求。数字一变就取消旧的——旧响应回来只会覆盖掉更新的分析。 */
    private var summaryJob: Job? = null

    init {
        viewModelScope.launch {
            baseCurrency = repository.observeBaseCurrency().first().orEmpty().ifBlank { "CNY" }
            _state.update { it.copy(currency = baseCurrency) }
            _state
                .map { it.period }
                .distinctUntilChanged()
                .collectLatest { period -> observePeriod(period) }
        }
    }

    fun onPeriodChange(period: StatsPeriod) {
        _state.update { it.copy(period = period) }
    }

    /** 周期内的分类合计 + 近 6 个月柱状——都是 SQL 侧 GROUP BY，界面只做展示映射。 */
    private suspend fun observePeriod(period: StatsPeriod) {
        val today = LocalDate.now()
        val thisMonth = YearMonth.from(today)
        val startMonth = thisMonth.minusMonths(period.months.toLong() - 1)
        val periodFrom = startMonth.atDay(1)
        val periodTo = thisMonth.atEndOfMonth()
        // 柱状图始终画近 6 个月，与周期切换无关
        val barsFrom = thisMonth.minusMonths(5).atDay(1)
        val barsTo = thisMonth.atEndOfMonth()

        combine(
            repository.observeCategoryTotals(AccountType.EXPENSE, periodFrom, periodTo),
            repository.observeCategoryTotals(AccountType.INCOME, periodFrom, periodTo),
            repository.observeMonthlyTotals(barsFrom, barsTo),
        ) { expenseCats, incomeCats, monthly ->
            Triple(expenseCats, incomeCats, monthly)
        }.collect { (expenseCats, incomeCats, monthly) ->
            val expenseTotal = expenseCats.sumOf { it.amountMinor }
            val incomeTotal = incomeCats.sumOf { it.amountMinor }
            val byCategory = expenseCats.toCategorySlices(expenseTotal)
            val bars = buildBars(thisMonth, monthly)
            _state.update {
                it.copy(
                    currency = baseCurrency,
                    expenseMinor = expenseTotal,
                    incomeMinor = incomeTotal,
                    balanceMinor = incomeTotal - expenseTotal,
                    expenseByCategory = byCategory,
                    monthlyBars = bars,
                    isLoading = false,
                )
            }
            refreshSummary(
                facts = MonthlySummaryFacts(
                    periodLabel = period.label,
                    from = periodFrom,
                    to = periodTo,
                    currency = baseCurrency,
                    expenseMinor = expenseTotal,
                    incomeMinor = incomeTotal,
                    dailyAverageMinor = expenseTotal / (period.months * DAYS_PER_MONTH),
                    byCategory = byCategory,
                    bars = bars,
                ),
                fallback = localSummaryText(byCategory, expenseTotal, incomeTotal, bars, period),
            )
        }
    }

    /**
     * 数字变了才去要一次 AI 分析，拿不到就退回本地模板。
     *
     * **本地降级不是可有可无的兜底，是常态**：实测调度层直答那一条配了 8 秒超时
     * （`config/routing.policy.yaml` 的 `direct_llm.timeout_ms`），同步路径下经常不够，
     * 于是「这次拿不到」会比想象中频繁。降级路径因此必须在用户看来是**一段正常的话**，
     * 而不是空白、错误框或一个转不完的圈。
     */
    private fun refreshSummary(facts: MonthlySummaryFacts, fallback: String) {
        if (facts == lastSummaryFacts) return
        lastSummaryFacts = facts
        summaryJob?.cancel()

        // 一个字都没记时不问模型：那是白花钱，而且它也变不出内容来
        if (facts.isEmpty) {
            _state.update { it.copy(summary = AiSummaryState.Ready(fallback, fromAi = false, confidence = null)) }
            return
        }

        _state.update { it.copy(summary = AiSummaryState.Loading) }
        summaryJob = viewModelScope.launch {
            val outcome = summarySource.summarize(facts)
            _state.update { current ->
                // 迟到的响应不许覆盖更新的分析：确认这批数字仍然是最新的那一批。
                // `cancel()` 拦不住已经越过挂起点的响应，所以这道判断是必要的，不是保险。
                if (lastSummaryFacts != facts) return@update current
                current.copy(
                    summary = when (outcome) {
                        is AiSummaryOutcome.Analyzed ->
                            AiSummaryState.Ready(outcome.text, fromAi = true, confidence = outcome.confidence)
                        is AiSummaryOutcome.Unavailable ->
                            AiSummaryState.Ready(fallback, fromAi = false, confidence = null)
                    },
                )
            }
        }
    }

    /**
     * 本地模板拼出来的那段话，用作 AI 不可用时的降级内容。
     *
     * 用 [buildInsights] 的全部条目而不只是第一条：几句短句连起来，长度和语气
     * 都更接近模型给的那段，用户不会因为「这次怎么只有一句话」而觉得出了问题。
     */
    private fun localSummaryText(
        byCategory: List<CategorySlice>,
        expenseTotal: Long,
        incomeTotal: Long,
        bars: List<MonthBar>,
        period: StatsPeriod,
    ): String = buildInsights(byCategory, expenseTotal, incomeTotal, bars, period)
        .joinToString(" ") { it.body }

    /** SQL 已按金额倒序，这里只补占比。 */
    private fun List<CategoryTotal>.toCategorySlices(expenseTotal: Long): List<CategorySlice> = map {
        CategorySlice(
            categoryId = it.categoryId,
            name = it.categoryName,
            amountMinor = it.amountMinor,
            ratio = if (expenseTotal == 0L) 0f else it.amountMinor.toFloat() / expenseTotal,
        )
    }

    /** 把 `yearMonth` 行转成 6 根柱子，缺的月份补零。 */
    private fun buildBars(thisMonth: YearMonth, monthly: List<MonthlyTotal>): List<MonthBar> {
        val byMonth = monthly.groupBy { it.yearMonth }
        return (5 downTo 0).map { offset ->
            val ym = thisMonth.minusMonths(offset.toLong())
            val rows = byMonth[ym.toString().take(7)].orEmpty()
            MonthBar(
                yearMonth = ym,
                expenseMinor = rows.firstOrNull { it.accountType == AccountType.EXPENSE }?.amountMinor ?: 0L,
                incomeMinor = rows.firstOrNull { it.accountType == AccountType.INCOME }?.amountMinor ?: 0L,
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
