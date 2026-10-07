package dev.dzsun.bookkeeping.feature.report

import dev.dzsun.bookkeeping.core.ledger.CategoryBudget
import dev.dzsun.bookkeeping.core.ledger.MonthAggregate
import java.time.LocalDate
import java.time.YearMonth

/**
 * 报表里的一个分类。
 *
 * [share] 与 [usage] 是**两个不同的比例**，别混：
 * - [share]：占当月总支出的多少（"钱花在哪了"）
 * - [usage]：占该分类预算的多少（"预算还够不够"）
 *
 * 只有设了预算才有 [usage]。同一个进度条画两种含义会误导人，所以界面按
 * [budgetMinor] 是否为空切换标签文案。
 */
data class CategoryReportLine(
    val categoryId: String,
    val name: String,
    val spentMinor: Long,
    val budgetMinor: Long?,
    val share: Float,
    val usage: Float?,
    val level: BudgetLevel,
    val overspendMinor: Long,
)

/** 一个月的报表，全部是展示口径算完的结果。 */
data class MonthlyReport(
    val yearMonth: YearMonth,
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
    /** 按金额降序。 */
    val categories: List<CategoryReportLine>,
    val dailyAverageMinor: Long,
    /** 日均的分母。[daysInAverage] 为 0 时日均无意义，界面该显示「—」而不是 ¥0。 */
    val daysInAverage: Int,
    val previousExpenseMinor: Long,
    /** 相对上月的涨跌。null 表示算不出来（上月没有支出）。 */
    val changeRatio: Float?,
    /** 超预算的分类，按金额降序。 */
    val overspent: List<CategoryReportLine>,
) {
    val hasAnyData: Boolean get() = incomeMinor != 0L || expenseMinor != 0L
}

/**
 * 聚合 → 报表。纯函数，无 Android 依赖，边界（月初、没数据、没预算、上月为零）都能直接单测。
 */
object MonthlyReportCalculator {

    /**
     * 币种没读出来时返回 null——**不是"空报表"，是"还算不出来"**。
     * 界面据此显示加载态，而不是显示一张全是 ¥0 的报表骗用户说这个月没花钱。
     */
    fun build(
        aggregate: MonthAggregate,
        budgets: List<CategoryBudget>,
        thresholds: BudgetThresholds,
        today: LocalDate,
    ): MonthlyReport? {
        val currency = aggregate.currency?.takeIf { it.isNotBlank() } ?: return null

        val budgetById = budgets.associate { it.categoryId to it.amountMinor }
        val total = aggregate.expenseMinor

        // 自己排一次序，不依赖上游 SQL 的 ORDER BY：数据层将来换成月度报表查询时，
        // 排序口径一漂，界面就会出现「最大的那类排在中间」这种说不清的展示
        val lines = aggregate.categories
            .map { row ->
                val budget = budgetById[row.categoryId]
                CategoryReportLine(
                    categoryId = row.categoryId,
                    name = row.categoryName,
                    spentMinor = row.amountMinor,
                    budgetMinor = budget,
                    share = if (total <= 0L) 0f else row.amountMinor.toFloat() / total,
                    usage = BudgetGate.usageRatio(row.amountMinor, budget),
                    level = BudgetGate.level(row.amountMinor, budget, thresholds),
                    overspendMinor = BudgetGate.overspendMinor(row.amountMinor, budget),
                )
            }
            .sortedWith(compareByDescending<CategoryReportLine> { it.spentMinor }.thenBy { it.name })

        val days = daysForAverage(aggregate.yearMonth, today)

        return MonthlyReport(
            yearMonth = aggregate.yearMonth,
            currency = currency,
            incomeMinor = aggregate.incomeMinor,
            expenseMinor = total,
            netMinor = aggregate.incomeMinor - total,
            categories = lines,
            dailyAverageMinor = if (days > 0) total / days else 0L,
            daysInAverage = days,
            previousExpenseMinor = aggregate.previousExpenseMinor,
            changeRatio = changeRatio(total, aggregate.previousExpenseMinor),
            overspent = lines.filter { it.level == BudgetLevel.OVER },
        )
    }

    /**
     * 日均的分母。
     *
     * 当月用**已经过去的天数**：拿整月天数去除，月初的日均会虚低好几倍，
     * 「这几天花得凶不凶」就完全看不出来了——而那正是日均唯一的用处。
     * 已过去的月份才用整月天数。
     */
    fun daysForAverage(yearMonth: YearMonth, today: LocalDate): Int {
        val thisMonth = YearMonth.from(today)
        return when {
            yearMonth == thisMonth -> today.dayOfMonth
            // 未来的月份没有"日均"可言，返回 0 让界面显示「—」
            yearMonth.isAfter(thisMonth) -> 0
            else -> yearMonth.lengthOfMonth()
        }
    }

    /**
     * 环比涨跌。
     *
     * 上月为 0 时返回 null：**涨了百分之多少是算不出来的**（除以零），
     * 硬凑成 +100% 会让"上月没记账"看起来像"这个月暴涨一倍"。
     */
    fun changeRatio(current: Long, previous: Long): Float? =
        if (previous <= 0L) null else (current - previous).toFloat() / previous
}
