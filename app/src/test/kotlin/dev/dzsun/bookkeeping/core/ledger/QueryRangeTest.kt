package dev.dzsun.bookkeeping.core.ledger

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时间词 → epoch day 闭区间。
 *
 * 这一层是**模型路径与词法路径共用的唯一一份日期算术**，所以边界钉得比别处密：
 * 月末、闰年二月、跨年的那一周。两条路各算各的时候，
 * 同一天在两个入口问「这个月花了多少」会得到不同的答案。
 */
class QueryRangeTest {

    private val today = LocalDate.of(2026, 10, 7)

    @Test
    fun `今天就是一天`() {
        val span = QueryRange.TODAY.span(today)!!
        assertEquals(LocalDate.of(2026, 10, 7).toEpochDay(), span.first)
        assertEquals(span.first, span.last)
    }

    @Test
    fun `昨天前天各是一天`() {
        assertEquals(LocalDate.of(2026, 10, 6).toEpochDay(), QueryRange.YESTERDAY.span(today)!!.first)
        assertEquals(LocalDate.of(2026, 10, 5).toEpochDay(), QueryRange.DAY_BEFORE_YESTERDAY.span(today)!!.first)
    }

    @Test
    fun `昨天跨月`() {
        val span = QueryRange.YESTERDAY.span(LocalDate.of(2026, 10, 1))!!
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), span.first)
    }

    @Test
    fun `本周是周一到周日`() {
        val span = QueryRange.THIS_WEEK.span(today)!!
        assertEquals(LocalDate.of(2026, 10, 5).toEpochDay(), span.first)   // 周一
        assertEquals(LocalDate.of(2026, 10, 11).toEpochDay(), span.last)   // 周日
    }

    @Test
    fun `上周跨年时落在去年`() {
        // 2026-01-02 是周五，它那一周的周一是 2025-12-29；再往前一周才是「上周」
        val span = QueryRange.LAST_WEEK.span(LocalDate.of(2026, 1, 2))!!
        assertEquals(LocalDate.of(2025, 12, 22).toEpochDay(), span.first)
        assertEquals(LocalDate.of(2025, 12, 28).toEpochDay(), span.last)
    }

    @Test
    fun `本月是 1 号到月末`() {
        val span = QueryRange.THIS_MONTH.span(today)!!
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), span.first)
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), span.last)
    }

    @Test
    fun `上个月在不跨年时是九月`() {
        val span = QueryRange.LAST_MONTH.span(today)!!
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), span.first)
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), span.last)
    }

    @Test
    fun `一月问上个月落在去年十二月`() {
        val span = QueryRange.LAST_MONTH.span(LocalDate.of(2026, 1, 15))!!
        assertEquals(LocalDate.of(2025, 12, 1).toEpochDay(), span.first)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), span.last)
    }

    @Test
    fun `闰年二月是 29 天`() {
        val span = QueryRange.THIS_MONTH.span(LocalDate.of(2024, 2, 10))!!
        assertEquals(LocalDate.of(2024, 2, 1).toEpochDay(), span.first)
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), span.last)
    }

    @Test
    fun `平年二月是 28 天`() {
        val span = QueryRange.THIS_MONTH.span(LocalDate.of(2026, 2, 10))!!
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), span.last)
    }

    @Test
    fun `今年与去年覆盖整年`() {
        val thisYear = QueryRange.THIS_YEAR.span(today)!!
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), thisYear.first)
        assertEquals(LocalDate.of(2026, 12, 31).toEpochDay(), thisYear.last)

        val lastYear = QueryRange.LAST_YEAR.span(today)!!
        assertEquals(LocalDate.of(2025, 1, 1).toEpochDay(), lastYear.first)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), lastYear.last)
    }

    @Test
    fun `不限时间没有区间`() {
        assertNull(QueryRange.ALL.span(today))
    }

    // —— 符号解析 ——

    @Test
    fun `枚举名原样认出来`() {
        assertEquals(QueryRange.THIS_MONTH, QueryRange.resolve("this_month"))
        assertEquals(QueryRange.LAST_YEAR, QueryRange.resolve("last_year"))
    }

    @Test
    fun `大小写与连字符都容错`() {
        assertEquals(QueryRange.THIS_MONTH, QueryRange.resolve("This-Month"))
        assertEquals(QueryRange.THIS_WEEK, QueryRange.resolve(" this week "))
    }

    @Test
    fun `中文时间词也认`() {
        assertEquals(QueryRange.THIS_MONTH, QueryRange.resolve("这个月"))
        assertEquals(QueryRange.LAST_WEEK, QueryRange.resolve("上周"))
    }

    @Test
    fun `没给时间就是不设限，而不是默认本月`() {
        // 静默默认成本月会把「一共花了多少」答成本月，用户看不出答的不是他问的
        assertEquals(QueryRange.ALL, QueryRange.resolve(null))
        assertEquals(QueryRange.ALL, QueryRange.resolve(""))
        assertEquals(QueryRange.ALL, QueryRange.resolve("   "))
        assertEquals(QueryRange.ALL, QueryRange.resolve("all"))
        assertEquals(QueryRange.ALL, QueryRange.resolve("全部"))
    }

    @Test
    fun `认不出来的词返回 null，不静默当不限时间`() {
        assertNull(QueryRange.resolve("last_3_months"))
        assertNull(QueryRange.resolve("2026-10"))
        assertNull(QueryRange.resolve("上个月底"))
    }

    @Test
    fun `ALL 没有中文词，不参与词法扫词`() {
        // 给它挂「一共」「全部」会改变词法路径既有的剥词结果
        assertEquals(emptyList<String>(), QueryRange.ALL.words.toList())
        assertEquals(9, QueryRange.dated.size)
    }
}
