package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.CategoryTotal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月度报表查询的日期窗口与聚合。
 *
 * 这一层能纯 JVM 测，是因为**窗口与折叠是我们自己的决定**：SQL 的 `BETWEEN`
 * 收的是这里算出的 epoch day，收入翻正是这里的取舍。真跑一次带数据的查询要靠
 * `tools/verify-report-sql.py`（端上不开库就没法建 Room），这里盖的是喂给它的输入。
 *
 * 三件必须钉住的：**正常月份覆盖整月**、**空月份返回零而不是崩**、**跨月/跨年不越界**。
 */
class MonthlyReportQueryTest {

    private fun epochDay(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toEpochDay()

    private fun days(window: DayWindow) = (window.toEpochDay - window.fromEpochDay) + 1

    // ------------------------------------------------------------ 正常月份

    @Test
    fun `正常月份：窗口正好覆盖整月，首尾两天都在内`() {
        val window = MonthlyReportQuery.window(YearMonth.of(2026, 10))
        assertEquals(epochDay(2026, 10, 1), window.fromEpochDay)
        assertEquals(epochDay(2026, 10, 31), window.toEpochDay)
        assertEquals(31L, days(window))
    }

    @Test
    fun `三十天的月份不多算一天`() {
        assertEquals(30L, days(MonthlyReportQuery.window(YearMonth.of(2026, 4))))
        assertEquals(30L, days(MonthlyReportQuery.window(YearMonth.of(2026, 9))))
    }

    // -------------------------------------------------------------- 跨月边界

    @Test
    fun `跨月边界：相邻月份首尾相接，既不重叠也不漏`() {
        val october = MonthlyReportQuery.window(YearMonth.of(2026, 10))
        val november = MonthlyReportQuery.window(YearMonth.of(2026, 11))
        // 10-31 的次日正是 11-01；差 1 就说明中间既没有漏掉一天，也没有多算一天
        assertEquals(october.toEpochDay + 1, november.fromEpochDay)
    }

    @Test
    fun `跨年边界：十二月翻到次年一月`() {
        val december = MonthlyReportQuery.window(YearMonth.of(2026, 12))
        val january = MonthlyReportQuery.window(YearMonth.of(2027, 1))
        assertEquals(epochDay(2026, 12, 31), december.toEpochDay)
        assertEquals(epochDay(2027, 1, 1), january.fromEpochDay)
        assertEquals(december.toEpochDay + 1, january.fromEpochDay)
    }

    @Test
    fun `闰年二月是二十九天，平年是二十八天`() {
        assertEquals(29L, days(MonthlyReportQuery.window(YearMonth.of(2024, 2))))
        assertEquals(28L, days(MonthlyReportQuery.window(YearMonth.of(2026, 2))))
    }

    @Test
    fun `一月的上一个月是去年的十二月，不是零月`() {
        // 手写 month - 1 会得到 0 月；走 minusMonths 才不会
        val previous = MonthlyReportQuery.previousWindow(YearMonth.of(2026, 1))
        assertEquals(MonthlyReportQuery.window(YearMonth.of(2025, 12)), previous)
        assertEquals(epochDay(2025, 12, 1), previous.fromEpochDay)
        assertEquals(epochDay(2025, 12, 31), previous.toEpochDay)
    }

    @Test
    fun `上个月的窗口与本月相邻`() {
        val previous = MonthlyReportQuery.previousWindow(YearMonth.of(2026, 10))
        assertEquals(MonthlyReportQuery.window(YearMonth.of(2026, 9)), previous)
        assertEquals(previous.toEpochDay + 1, MonthlyReportQuery.window(YearMonth.of(2026, 10)).fromEpochDay)
    }

    // ------------------------------------------------------ 空月份 / 正常统计

    @Test
    fun `空月份返回零，不是 null 也不崩`() {
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 10),
            currency = "CNY",
            incomeTotal = 0L,
            expenseTotal = 0L,
            categories = emptyList(),
            previousExpenseTotal = 0L,
        )
        assertEquals(0L, aggregate.incomeMinor)
        assertEquals(0L, aggregate.expenseMinor)
        assertEquals(0L, aggregate.previousExpenseMinor)
        assertTrue(aggregate.categories.isEmpty())
        // 币种在，说明"这个月确实没花钱"，与下面的"还算不出来"是两回事
        assertEquals("CNY", aggregate.currency)
    }

    @Test
    fun `正常月份：收入翻正，支出与分类原样透传`() {
        val categories = listOf(
            CategoryTotal("expense.food", "餐饮", 400_00L),
            CategoryTotal("expense.transport", "交通", 100_00L),
        )
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 10),
            currency = "CNY",
            // 收入侧分录在账上是负数（贷方）
            incomeTotal = -1_500_00L,
            expenseTotal = 500_00L,
            categories = categories,
            previousExpenseTotal = 200_00L,
        )
        assertEquals(1_500_00L, aggregate.incomeMinor)
        assertEquals(500_00L, aggregate.expenseMinor)
        assertEquals(200_00L, aggregate.previousExpenseMinor)
        assertEquals(categories, aggregate.categories)
    }

    @Test
    fun `只有支出没有收入时收入是零而不是负零`() {
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 10),
            currency = "CNY",
            incomeTotal = 0L,
            expenseTotal = 88_00L,
            categories = emptyList(),
            previousExpenseTotal = 0L,
        )
        assertEquals(0L, aggregate.incomeMinor)
    }

    @Test
    fun `冲减月里收入为负时翻正后仍是负的（该退的钱要看得出来）`() {
        // 收入合计是正的（贷方被反冲成正数），翻正后变成负数——正是"这个月净退钱"
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 10),
            currency = "CNY",
            incomeTotal = 300_00L,
            expenseTotal = 0L,
            categories = emptyList(),
            previousExpenseTotal = 0L,
        )
        assertEquals(-300_00L, aggregate.incomeMinor)
    }

    @Test
    fun `科目表还没读出来时是零值聚合且币种为空`() {
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 10),
            currency = null,
            incomeTotal = 0L,
            expenseTotal = 0L,
            categories = emptyList(),
            previousExpenseTotal = 0L,
        )
        // 与"空月份"数字上一样，靠 currency 区分——界面据此显示加载态而不是一张 ¥0 报表
        assertNull(aggregate.currency)
    }

    @Test
    fun `聚合保留哪个月的信息，界面靠它防止把旧月份的数字配新标题`() {
        val aggregate = MonthlyReportQuery.assemble(
            yearMonth = YearMonth.of(2026, 3),
            currency = "CNY",
            incomeTotal = 0L,
            expenseTotal = 0L,
            categories = emptyList(),
            previousExpenseTotal = 0L,
        )
        assertEquals(YearMonth.of(2026, 3), aggregate.yearMonth)
    }
}
