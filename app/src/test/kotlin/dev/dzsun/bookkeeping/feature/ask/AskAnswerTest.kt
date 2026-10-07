package dev.dzsun.bookkeeping.feature.ask

import dev.dzsun.bookkeeping.core.ledger.LedgerGroup
import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.LedgerQueryResult
import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查询结果 → 中文答案」这一层。
 *
 * 它最容易悄悄出错、出错了又最难看出来的地方是**措辞**：
 * 「本月支出 ¥0.00，共 0 笔」与「本月没有支出记录」数字完全相同、含义完全不同——
 * 前者会让用户以为账记了只是金额是零，于是去翻账本找一笔根本不存在的记录。
 */
class AskAnswerTest {

    private val today = LocalDate.of(2026, 10, 6)
    private val thisMonth = YearMonth.of(2026, 10)

    private fun result(
        minor: Long,
        count: Int,
        groups: List<LedgerGroup> = emptyList(),
    ) = LedgerQueryResult(
        total = Money.of(minor, "CNY"),
        entryCount = count,
        groups = groups,
    )

    private fun query(
        from: Long? = thisMonth.atDay(1).toEpochDay(),
        to: Long? = thisMonth.atEndOfMonth().toEpochDay(),
        direction: QueryDirection = QueryDirection.EXPENSE,
        categoryName: String? = null,
        merchant: String? = null,
        grouping: QueryGrouping = QueryGrouping.NONE,
        limit: Int = LedgerQuery.DEFAULT_LIMIT,
    ) = LedgerQuery(
        fromEpochDay = from,
        toEpochDay = to,
        direction = direction,
        categoryName = categoryName,
        merchant = merchant,
        grouping = grouping,
        limit = limit,
    )

    private fun answer(q: LedgerQuery, r: LedgerQueryResult) =
        AskAnswerFormatter.format("问题", q, r, today)

    // —— 零笔与有笔 ——

    @Test
    fun `零笔说没有记录，不说金额是零`() {
        val a = answer(query(), result(0L, 0))
        assertTrue(a.headline, a.headline.contains("没有记录"))
        assertFalse("零笔不该摆出一个金额", a.headline.contains("0 笔"))
    }

    @Test
    fun `有笔时给出金额与笔数`() {
        val a = answer(query(), result(38_50L, 3))
        assertTrue(a.headline, a.headline.contains("3 笔"))
    }

    // —— 时间范围的说法 ——

    @Test
    fun `整月对齐才叫本月`() {
        assertEquals("本月", AskAnswerFormatter.describeRange(
            thisMonth.atDay(1).toEpochDay(), thisMonth.atEndOfMonth().toEpochDay(), today,
        ))
    }

    @Test
    fun `只问到月中时说的是具体日期，不是本月`() {
        // 说成「本月」就把范围说大了——用户问的是 1 号到 15 号
        val text = AskAnswerFormatter.describeRange(
            thisMonth.atDay(1).toEpochDay(), thisMonth.atDay(15).toEpochDay(), today,
        )
        assertFalse(text, text == "本月")
        assertTrue(text, text.contains("2026-10-01"))
        assertTrue(text, text.contains("2026-10-15"))
    }

    @Test
    fun `上月认得出来`() {
        val last = thisMonth.minusMonths(1)
        assertEquals("上月", AskAnswerFormatter.describeRange(
            last.atDay(1).toEpochDay(), last.atEndOfMonth().toEpochDay(), today,
        ))
    }

    @Test
    fun `两端都不给是全时间`() {
        assertEquals("全部时间", AskAnswerFormatter.describeRange(null, null, today))
    }

    @Test
    fun `只有一端时说得清是哪一端`() {
        val from = thisMonth.atDay(1).toEpochDay()
        val to = thisMonth.atDay(15).toEpochDay()
        assertTrue(AskAnswerFormatter.describeRange(from, null, today).endsWith("起"))
        assertTrue(AskAnswerFormatter.describeRange(null, to, today).startsWith("截至"))
    }

    // —— 方向与筛选 ——

    @Test
    fun `三个方向各有各的说法`() {
        assertTrue(answer(query(direction = QueryDirection.EXPENSE), result(1L, 1)).headline.contains("支出"))
        assertTrue(answer(query(direction = QueryDirection.INCOME), result(1L, 1)).headline.contains("收入"))
        assertTrue(answer(query(direction = QueryDirection.BOTH), result(1L, 1)).headline.contains("收支净额"))
    }

    @Test
    fun `筛选条件写进作用域，答案里看得出问的是哪一档`() {
        assertTrue(answer(query(categoryName = "餐饮"), result(1L, 1)).headline.contains("餐饮"))
        assertTrue(answer(query(merchant = "星巴克"), result(1L, 1)).headline.contains("星巴克"))
    }

    // —— 脚注 ——

    @Test
    fun `币种口径总写在脚注里`() {
        val a = answer(query(), result(1L, 1))
        assertTrue(a.footnote, a.footnote!!.contains("CNY"))
    }

    @Test
    fun `分组被上限截断时要说出来`() {
        // 不说的话用户以为这就是全部——合计是对的，两件事摆一起会让人怀疑数字
        val groups = (1..3).map { LedgerGroup("k$it", Money.of(1L, "CNY"), 1) }
        val a = answer(
            query(grouping = QueryGrouping.CATEGORY, limit = 3),
            result(3L, 3, groups),
        )
        assertTrue(a.footnote, a.footnote!!.contains("只显示"))
    }

    @Test
    fun `分组没被截断时不提上限`() {
        val groups = listOf(LedgerGroup("k1", Money.of(1L, "CNY"), 1))
        val a = answer(query(grouping = QueryGrouping.CATEGORY, limit = 20), result(1L, 1, groups))
        assertFalse(a.footnote ?: "", (a.footnote ?: "").contains("只显示"))
    }

    @Test
    fun `收支净额带一句解释，免得被读成支出`() {
        val a = answer(query(direction = QueryDirection.BOTH), result(1L, 1))
        assertTrue(a.footnote, a.footnote!!.contains("净额"))
    }

    // —— 明细 ——

    @Test
    fun `分组明细逐条成行`() {
        val groups = listOf(
            LedgerGroup("餐饮", Money.of(38_50L, "CNY"), 2),
            LedgerGroup("交通", Money.of(12_00L, "CNY"), 1),
        )
        val a = answer(query(grouping = QueryGrouping.CATEGORY), result(50_50L, 3, groups))
        assertEquals(2, a.detail.size)
        assertTrue(a.detail[0], a.detail[0].contains("餐饮"))
        assertTrue(a.detail[0], a.detail[0].contains("2 笔"))
    }

    @Test
    fun `问的那句话原样带回，用户看得见自己问的是什么`() {
        val a = AskAnswerFormatter.format("这个月花了多少", query(), result(1L, 1), today)
        assertEquals("这个月花了多少", a.question)
    }
}
