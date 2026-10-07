package dev.dzsun.bookkeeping.core.ledger

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「一句中文 → 一次结构化查询」这一层。
 *
 * 它错起来**最难看出来的地方是日期**：差一天会把上月的一笔算进本月，
 * 差一个月会让「上个月花了多少」答成本月的数字——两者都长着一副正常的样子。
 * 所以边界（月末、跨年、闰年二月、近 N）逐条钉死，而不是只测一句「这个月」。
 *
 * 另一半是**不该翻的时候要翻不出来**：把「今天天气」当成一次问账，
 * 会拿「天气」当商户名查出一句「没有记录」，看起来像答了，其实答非所问。
 */
class LocalQueryTranslatorTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun parse(
        question: String,
        today: LocalDate = this.today,
        categories: Set<String> = emptySet(),
    ) = parseQuery(question, today, categories)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    // —— 正常翻译 ——

    @Test
    fun `这个月花了多少`() {
        val q = parse("这个月花了多少")!!
        assertEquals(QueryDirection.EXPENSE, q.direction)
        assertEquals(day(2026, 10, 1), q.fromEpochDay)
        assertEquals(day(2026, 10, 31), q.toEpochDay)
        assertNull(q.categoryName)
        assertNull(q.merchant)
        assertEquals(QueryGrouping.NONE, q.grouping)
    }

    @Test
    fun `星巴克花了几次——商户做包含匹配`() {
        val q = parse("星巴克花了几次")!!
        assertEquals(QueryDirection.EXPENSE, q.direction)
        assertEquals("星巴克", q.merchant)
        // 没提时间就是不设限，不是默认成这个月
        assertNull(q.fromEpochDay)
        assertNull(q.toEpochDay)
    }

    @Test
    fun `上个月收入多少`() {
        val q = parse("上个月收入多少")!!
        assertEquals(QueryDirection.INCOME, q.direction)
        assertEquals(day(2026, 9, 1), q.fromEpochDay)
        assertEquals(day(2026, 9, 30), q.toEpochDay)
    }

    @Test
    fun `收支一起问时是净额`() {
        val q = parse("今年收支")!!
        assertEquals(QueryDirection.BOTH, q.direction)
        assertEquals(day(2026, 1, 1), q.fromEpochDay)
        assertEquals(day(2026, 12, 31), q.toEpochDay)
    }

    @Test
    fun `分组维度认得出来`() {
        assertEquals(QueryGrouping.MONTH, parse("按月看看今年花了多少")!!.grouping)
        assertEquals(QueryGrouping.CATEGORY, parse("各分类花了多少")!!.grouping)
        assertEquals(QueryGrouping.MERCHANT, parse("按商户看这个月花了多少")!!.grouping)
    }

    // —— 分类从科目表读，不写死 ——

    @Test
    fun `分类名对上科目表时认成分类`() {
        val q = parse("这个月的餐饮花了多少", categories = setOf("餐饮", "交通"))!!
        assertEquals("餐饮", q.categoryName)
        assertNull(q.merchant)
    }

    @Test
    fun `科目表里没有这个词时退化成商户包含匹配`() {
        // 分类是数据不是代码：同一句话在另一套科目表下就该是另一种理解
        val q = parse("这个月的餐饮花了多少", categories = setOf("交通"))!!
        assertNull(q.categoryName)
        assertEquals("餐饮", q.merchant)
    }

    @Test
    fun `分类名里含方向词时，长的那一个赢`() {
        // 「其他支出」里有「支出」；先剥方向词会把名字切碎，剩下「其他」被误当商户
        val q = parse("其他支出花了多少", categories = setOf("其他支出"))!!
        assertEquals("其他支出", q.categoryName)
        assertNull(q.merchant)
    }

    @Test
    fun `科目表读不到时不影响一笔普通的查询`() {
        val q = parse("这个月花了多少", categories = emptySet())!!
        assertEquals(QueryDirection.EXPENSE, q.direction)
    }

    // —— 边界时间词 ——

    @Test
    fun `月末问本月，收在当月最后一天`() {
        val q = parse("这个月花了多少", today = LocalDate.of(2026, 10, 31))!!
        assertEquals(day(2026, 10, 1), q.fromEpochDay)
        assertEquals(day(2026, 10, 31), q.toEpochDay)
    }

    @Test
    fun `闰年二月有二十九天`() {
        val q = parse("本月花了多少", today = LocalDate.of(2024, 2, 15))!!
        assertEquals(day(2024, 2, 1), q.fromEpochDay)
        assertEquals(day(2024, 2, 29), q.toEpochDay)
    }

    @Test
    fun `跨年那周的「上周」落在去年`() {
        // 2026-01-02 是周五，那一周的周一是 2025-12-29，上周则是 12-22 ~ 12-28
        val q = parse("上周花了多少", today = LocalDate.of(2026, 1, 2))!!
        assertEquals(day(2025, 12, 22), q.fromEpochDay)
        assertEquals(day(2025, 12, 28), q.toEpochDay)
    }

    @Test
    fun `本周从周一算到周日`() {
        val q = parse("本周花了多少", today = LocalDate.of(2026, 10, 6))!!
        // 2026-10-06 是周二，周一为 10-05，周日为 10-11
        assertEquals(day(2026, 10, 5), q.fromEpochDay)
        assertEquals(day(2026, 10, 11), q.toEpochDay)
    }

    @Test
    fun `昨天跨月时落在上个月最后一天`() {
        val q = parse("昨天花了多少", today = LocalDate.of(2026, 3, 1))!!
        assertEquals(day(2026, 2, 28), q.fromEpochDay)
        assertEquals(day(2026, 2, 28), q.toEpochDay)
    }

    @Test
    fun `去年是整年`() {
        val q = parse("去年花了多少", today = LocalDate.of(2026, 3, 5))!!
        assertEquals(day(2025, 1, 1), q.fromEpochDay)
        assertEquals(day(2025, 12, 31), q.toEpochDay)
    }

    @Test
    fun `近三个月是本月往前两个整月，不是倒推九十天`() {
        val q = parse("近三个月花了多少")!!
        assertEquals(day(2026, 8, 1), q.fromEpochDay)
        assertEquals(day(2026, 10, 6), q.toEpochDay)
    }

    @Test
    fun `中文数字与阿拉伯数字都认`() {
        assertEquals(day(2026, 8, 1), parse("近三个月花了多少")!!.fromEpochDay)
        assertEquals(day(2026, 8, 1), parse("最近3个月花了多少")!!.fromEpochDay)
    }

    @Test
    fun `近七天含今天，一共七天`() {
        val q = parse("最近7天花了多少")!!
        assertEquals(day(2026, 9, 30), q.fromEpochDay)
        assertEquals(day(2026, 10, 6), q.toEpochDay)
    }

    // —— 方向缺省 ——

    @Test
    fun `没有方向词时兜底成支出——这是降级路径和主路径的分界`() {
        // 主路径（调度层）里 direction 必填，缺了整条查询不成立；
        // 本地降级必须答最常见的问法，所以这里兜底成支出
        assertEquals(QueryDirection.EXPENSE, parse("星巴克几次")!!.direction)
    }

    // —— 认不出来时的降级 ——

    @Test
    fun `空问题翻不出来`() {
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun `寒暄翻不出来`() {
        assertNull(parse("你好"))
        assertNull(parse("帮我看看"))
        assertNull(parse("随便说点什么"))
    }

    @Test
    fun `光有时间词不算一次问账`() {
        // 「今天天气」里有「今天」，但没有任何"要算什么"的信号。放行的话
        // 会拿「天气」当商户名查出「没有记录」，看着像答了其实答非所问
        assertNull(parse("今天天气怎么样"))
        assertNull(parse("这个月"))
    }

    // —— 商户提取的两道闸门 ——

    @Test
    fun `店名里的虚词不被打碎`() {
        // 全局剥掉「和」会把「和平饭店」切成「平饭店」——不报错，只是永远查不到
        assertEquals("和平饭店", parse("和平饭店花了多少")!!.merchant)
    }

    @Test
    fun `客套话不当商户，答案仍落在正确的时间范围上`() {
        val q = parse("你好请问这个月花了多少")!!
        assertNull("寒暄不该变成商户筛选条件", q.merchant)
        assertEquals(day(2026, 10, 1), q.fromEpochDay)
    }

    @Test
    fun `一整句话不会被当成商户名`() {
        // 长句或带标点，一律当作没提取到商户，而不是硬塞给查询
        assertNull(parse("我想知道这个月花了多少啊！")!!.merchant)
    }

    @Test
    fun `问的那句话里认得出的筛选条件仍然带过`() {
        val q = parse("上个月在星巴克花了多少钱")!!
        assertEquals("星巴克", q.merchant)
        assertEquals(day(2026, 9, 1), q.fromEpochDay)
    }

    @Test
    fun `翻出来的查询一定是构造得出来的`() {
        // LedgerQuery 构造期会拒绝空串筛选、倒置区间；能从这里出去就说明过了那一关
        assertNotNull(parse("这个月星巴克花了多少"))
    }
}
