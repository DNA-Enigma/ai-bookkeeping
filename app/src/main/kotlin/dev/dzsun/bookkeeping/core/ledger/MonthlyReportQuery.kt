package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.CategoryTotal
import java.time.YearMonth

/**
 * 某个分类的月度预算上限。
 *
 * 金额一律**整数最小单位**，与账本其余部分同一口径——预算也是钱，
 * 用浮点存的话「餐饮 1100 元」这个数本身就先失真了。
 *
 * 类型定义在数据层（而不是报表页），因为**谁拥有那份数据谁定义它**：
 * 预算是 `budget` 表里的行，不再是界面侧的一份 prefs。
 */
data class CategoryBudget(
    val categoryId: String,
    val amountMinor: Long,
)

/**
 * 一个月的**原始聚合**——只放「查出来的数」，不放任何展示口径。
 *
 * 占比、日均、环比、预算档位都在界面侧的 `MonthlyReportCalculator` 里算。
 * 分成两层是因为**查与算是两件事**：查要对着一本会越来越长的账，
 * 算要对着用户的读法（"这几天花得凶不凶"），两边的边界完全不同，
 * 混在一处就没法只测其中一件。
 *
 * 这里也刻意**不放净额与占比**：它们是 `incomeMinor - expenseMinor` 与
 * 一笔除法，放进来就有两个地方各算一份，迟早会漂成一个说 A、一个说 B。
 *
 * [currency] 为 null 表示科目表还没读出来（库里还没有账户行）。**那不是"空报表"，
 * 是"还算不出来"**——界面据此显示加载态，而不是显示一张全是 ¥0 的报表骗用户说这个月没花钱。
 */
data class MonthAggregate(
    val yearMonth: YearMonth,
    val currency: String?,
    /** 收入，正数。 */
    val incomeMinor: Long,
    /** 支出，正数。 */
    val expenseMinor: Long,
    /** 支出侧分类合计。**顺序不保证**，消费方自己按展示口径排序。 */
    val categories: List<CategoryTotal>,
    /** 上月支出，用于环比。0 表示上月没有支出。 */
    val previousExpenseMinor: Long,
)

/**
 * 一个日期窗口，两端都是 **epoch day 且闭区间**——
 * SQL 侧直接吃 `BETWEEN fromEpochDay AND toEpochDay`。
 *
 * 用 epoch day 而不是时间戳：记账的日期是日历概念（`journal.dateEpochDay`），
 * 存时间戳会因为时区在不同设备上显示成不同的日子。天与天之间正好差 1，
 * 所以「10-31 与 11-01 相邻但不重叠」是精确的，没有跨月漏算或多算的余地。
 */
data class DayWindow(
    val fromEpochDay: Long,
    val toEpochDay: Long,
)

/**
 * 月度报表的日期窗口与聚合。纯函数、无 Android 依赖——
 * 跨月边界（闰年二月、12 月翻到次年 1 月）都能直接单测，不必开库。
 */
object MonthlyReportQuery {

    /** 某个月的窗口，含首尾两天。 */
    fun window(yearMonth: YearMonth): DayWindow = DayWindow(
        fromEpochDay = yearMonth.atDay(1).toEpochDay(),
        toEpochDay = yearMonth.atEndOfMonth().toEpochDay(),
    )

    /**
     * 上个月的窗口，用于环比。
     *
     * 走 [YearMonth.minusMonths] 而不是自己减 1——1 月的前一个月是**去年 12 月**，
     * 手写 `month - 1` 会得到 0 月。
     */
    fun previousWindow(yearMonth: YearMonth): DayWindow = window(yearMonth.minusMonths(1))

    /**
     * 把四个查询的结果折成一个月的聚合。
     *
     * **收入侧的分录在账上是负数**（贷方），这里翻正——与账目列表同一个口径，
     * 否则报表上的「本月收入」是负的，而列表里同一个月是正的。
     *
     * 科目表还没读出来（[currency] 为空）时，四个合计都是 0、分类为空，
     * 走的是同一条路径：**空月份与未就绪产出的是同一个零值聚合**，
     * 由 [MonthAggregate.currency] 是否为空来区分两者。
     */
    fun assemble(
        yearMonth: YearMonth,
        currency: String?,
        incomeTotal: Long,
        expenseTotal: Long,
        categories: List<CategoryTotal>,
        previousExpenseTotal: Long,
    ): MonthAggregate = MonthAggregate(
        yearMonth = yearMonth,
        currency = currency,
        incomeMinor = -incomeTotal,
        expenseMinor = expenseTotal,
        categories = categories,
        previousExpenseMinor = previousExpenseTotal,
    )
}
