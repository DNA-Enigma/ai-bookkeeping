package dev.dzsun.bookkeeping.feature.report

import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.ledger.CategoryBudget
import dev.dzsun.bookkeeping.core.ledger.MonthAggregate
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聚合 → 报表的展示口径。
 *
 * 这里的数字用户会直接拿来做决定（这个月花超了没有、比上月好还是差），
 * 所以边界都得有测试：月初的日均、上月没支出的环比、没设预算的分类。
 */
class MonthlyReportCalculatorTest {

    private val month = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 10)

    private fun aggregate(
        currency: String? = "CNY",
        incomeMinor: Long = 0L,
        expenseMinor: Long = 0L,
        categories: List<CategoryTotal> = emptyList(),
        previousExpenseMinor: Long = 0L,
        yearMonth: YearMonth = month,
    ) = MonthAggregate(
        yearMonth = yearMonth,
        currency = currency,
        incomeMinor = incomeMinor,
        expenseMinor = expenseMinor,
        categories = categories,
        previousExpenseMinor = previousExpenseMinor,
    )

    private fun category(id: String, name: String, amountMinor: Long) =
        CategoryTotal(categoryId = id, categoryName = name, amountMinor = amountMinor)

    @Test
    fun `币种没读出来时返回 null，而不是一张零报表`() {
        // 返回 0 元报表的话，用户会看到"这个月没花钱"——那是在骗他
        assertNull(MonthlyReportCalculator.build(aggregate(currency = null), emptyList(), BudgetThresholds(), today))
        assertNull(MonthlyReportCalculator.build(aggregate(currency = "  "), emptyList(), BudgetThresholds(), today))
    }

    @Test
    fun `收入支出与净额`() {
        val report = MonthlyReportCalculator.build(
            aggregate(incomeMinor = 1_500_000L, expenseMinor = 500_000L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(1_500_000L, report.incomeMinor)
        assertEquals(500_000L, report.expenseMinor)
        assertEquals(1_000_000L, report.netMinor)
        assertTrue(report.hasAnyData)
    }

    @Test
    fun `入不敷出时净额为负`() {
        val report = MonthlyReportCalculator.build(
            aggregate(incomeMinor = 100_000L, expenseMinor = 300_000L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(-200_000L, report.netMinor)
    }

    @Test
    fun `分类一律按金额降序，不依赖上游的排序`() {
        // 上游 SQL 一换实现，排序口径就可能漂——最大的那类排在中间是说不清的展示
        val report = MonthlyReportCalculator.build(
            aggregate(
                expenseMinor = 600_000L,
                categories = listOf(
                    category("expense.transport", "交通", 100_000L),
                    category("expense.food", "餐饮", 400_000L),
                    category("expense.shopping", "购物", 100_000L),
                ),
            ),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(listOf("餐饮", "交通", "购物"), report.categories.map { it.name })
        assertEquals(400_000L / 600_000f, report.categories.first().share)
    }

    @Test
    fun `当月日均按已经过去的天数算`() {
        // 10 号用整月 31 天去除，日均会虚低三倍，"这几天花得凶不凶"就看不出来了
        val report = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 310_000L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(10, report.daysInAverage)
        assertEquals(31_000L, report.dailyAverageMinor)
    }

    @Test
    fun `已过去的月份日均按整月天数算`() {
        val september = YearMonth.of(2026, 9)
        val report = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 300_000L, yearMonth = september),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(30, report.daysInAverage)
        assertEquals(10_000L, report.dailyAverageMinor)
    }

    @Test
    fun `未来的月份没有日均可言`() {
        // 分母为 0，界面据此显示「—」，而不是显示一个 ¥0 假装花得少
        val november = YearMonth.of(2026, 11)
        val report = MonthlyReportCalculator.build(
            aggregate(yearMonth = november),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(0, report.daysInAverage)
        assertEquals(0L, report.dailyAverageMinor)
    }

    @Test
    fun `上月没有支出时环比算不出来`() {
        val report = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 300_000L, previousExpenseMinor = 0L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        // 硬凑成 +100% 会让"上月没记账"看起来像"这个月暴涨一倍"
        assertNull(report.changeRatio)
    }

    @Test
    fun `环比涨跌是两个方向`() {
        val up = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 300_000L, previousExpenseMinor = 200_000L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(0.5f, up.changeRatio)

        val down = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 150_000L, previousExpenseMinor = 200_000L),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(-0.25f, down.changeRatio)
    }

    @Test
    fun `没有预算的分类是 UNSET，不是 OK`() {
        val report = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 100_000L, categories = listOf(category("expense.food", "餐饮", 100_000L))),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        val line = report.categories.single()
        assertEquals(BudgetLevel.UNSET, line.level)
        assertNull(line.usage)
        assertNull(line.budgetMinor)
    }

    @Test
    fun `超预算的分类进超支列表并给出超出金额`() {
        val report = MonthlyReportCalculator.build(
            aggregate(
                expenseMinor = 250_000L,
                categories = listOf(
                    category("expense.food", "餐饮", 120_000L),
                    category("expense.transport", "交通", 80_000L),
                    category("expense.shopping", "购物", 50_000L),
                ),
            ),
            listOf(
                CategoryBudget("expense.food", 100_000L),
                CategoryBudget("expense.transport", 100_000L),
                // 购物没设预算
            ),
            BudgetThresholds(),
            today,
        )!!
        val over = report.overspent
        assertEquals(listOf("餐饮"), over.map { it.name })
        assertEquals(20_000L, over.single().overspendMinor)
        assertEquals(BudgetLevel.NEAR, report.categories.first { it.name == "交通" }.level)
        assertEquals(BudgetLevel.UNSET, report.categories.first { it.name == "购物" }.level)
    }

    @Test
    fun `阈值改变会改变同一份数据的判定`() {
        // 1000 元的预算花掉 900 元
        val aggregate = aggregate(
            expenseMinor = 90_000L,
            categories = listOf(category("expense.food", "餐饮", 90_000L)),
        )
        val budgets = listOf(CategoryBudget("expense.food", 100_000L))

        // 默认 80/100：算「接近」
        val byDefault = MonthlyReportCalculator.build(aggregate, budgets, BudgetThresholds(), today)!!
        assertEquals(BudgetLevel.NEAR, byDefault.categories.single().level)

        // 把「接近」线提到 95%：同一笔就不该再提醒了
        val quiet = MonthlyReportCalculator.build(
            aggregate,
            budgets,
            BudgetThresholds(nearRatio = 0.95f, overRatio = 1.0f),
            today,
        )!!
        assertEquals(BudgetLevel.OK, quiet.categories.single().level)

        // 把「超了」线抬到 120% 的缓冲：花光 100% 反而不算超了
        val buffered = MonthlyReportCalculator.build(
            aggregate,
            budgets,
            BudgetThresholds(nearRatio = 0.8f, overRatio = 1.2f),
            today,
        )!!
        assertEquals(BudgetLevel.NEAR, buffered.categories.single().level)
        assertTrue(buffered.overspent.isEmpty())
    }

    @Test
    fun `空月份是一张没有数据的报表而不是崩溃`() {
        val report = MonthlyReportCalculator.build(aggregate(), emptyList(), BudgetThresholds(), today)!!
        assertFalse(report.hasAnyData)
        assertTrue(report.categories.isEmpty())
        assertTrue(report.overspent.isEmpty())
        assertEquals(0L, report.expenseMinor)
    }

    @Test
    fun `支出为零时分类占比不除零`() {
        // 数据理论上不该出现（有分类合计就必然有支出），但除零会产出 NaN 让进度条画崩
        val report = MonthlyReportCalculator.build(
            aggregate(expenseMinor = 0L, categories = listOf(category("expense.food", "餐饮", 0L))),
            emptyList(),
            BudgetThresholds(),
            today,
        )!!
        assertEquals(0f, report.categories.single().share)
    }
}
