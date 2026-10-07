package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「调度层下发的查询结构」→「本地可执行的查询」这一段映射。
 *
 * 这一段错的话，用户得到的是一个**看着挺像的错数字**——方向翻错会把收入答成支出，
 * 日期差一天会把上个月的一笔算进这个月，而界面上没有任何地方看得出不对。
 * 所以每条不成立的输入都必须落成 null（界面如实说「翻译不了」），绝不猜一个默认值。
 */
class LedgerQuerySpecTest {

    private fun spec(
        from: String? = null,
        to: String? = null,
        direction: String? = "expense",
        category: String? = null,
        merchant: String? = null,
        groupBy: String? = null,
        limit: Int? = null,
    ) = LedgerQuerySpec(
        from = from,
        to = to,
        direction = direction,
        category = category,
        merchant = merchant,
        groupBy = groupBy,
        limit = limit,
    )

    // —— 方向：错的方向比没有方向更糟 ——

    @Test
    fun `方向缺失时整条查询不成立，不给默认方向`() {
        // 默认成 expense 会把「我这个月收入多少」静默答成一个支出数字
        assertNull(spec(direction = null).toQuery())
    }

    @Test
    fun `认不出的方向同样不成立`() {
        assertNull(spec(direction = "转账").toQuery())
    }

    @Test
    fun `方向大小写不敏感`() {
        assertEquals(QueryDirection.EXPENSE, spec(direction = "EXPENSE").toQuery()?.direction)
        assertEquals(QueryDirection.INCOME, spec(direction = "Income").toQuery()?.direction)
        assertEquals(QueryDirection.BOTH, spec(direction = "both").toQuery()?.direction)
    }

    // —— 日期 ——

    @Test
    fun `ISO 日期解析成 epoch day，两端都可空`() {
        val query = spec(from = "2026-10-01", to = "2026-10-31").toQuery()
        assertNotNull(query)
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), query!!.fromEpochDay)
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), query.toEpochDay)

        val open = spec().toQuery()
        assertNull(open?.fromEpochDay)
        assertNull(open?.toEpochDay)
    }

    @Test
    fun `日期格式不对时整条查询作废，不猜一个近似日期`() {
        assertNull(spec(from = "2026/10/01").toQuery())
        assertNull(spec(to = "上月").toQuery())
    }

    @Test
    fun `起点晚于终点时作废`() {
        assertNull(spec(from = "2026-10-31", to = "2026-10-01").toQuery())
    }

    // —— 分组与上限 ——

    @Test
    fun `分组维度认不出来时退回不分组，合计仍然成立`() {
        // 方向错了数字就是错的；分组只是详略，少一层明细不至于骗人
        val query = spec(groupBy = "按心情").toQuery()
        assertNotNull(query)
        assertEquals(QueryGrouping.NONE, query!!.grouping)
    }

    @Test
    fun `分组维度认得出来时原样带过`() {
        assertEquals(QueryGrouping.MERCHANT, spec(groupBy = "merchant").toQuery()?.grouping)
        assertEquals(QueryGrouping.MONTH, spec(groupBy = "MONTH").toQuery()?.grouping)
    }

    @Test
    fun `分组上限非正时退回默认值，不落成零行`() {
        assertEquals(
            dev.dzsun.bookkeeping.core.ledger.LedgerQuery.DEFAULT_LIMIT,
            spec(limit = 0).toQuery()?.limit,
        )
        assertEquals(5, spec(limit = 5).toQuery()?.limit)
    }

    // —— 筛选条件 ——

    @Test
    fun `空白的分类名与商户名当作没给，不当成空串匹配`() {
        // 空串的 LIKE '%%' 会匹配全部，等于静默把「星巴克花了几次」答成「这个月花了多少」
        val blank = spec(category = "  ", merchant = "  ").toQuery()
        assertNull(blank?.categoryName)
        assertNull(blank?.merchant)
    }

    @Test
    fun `筛选条件原样带过`() {
        val query = spec(category = "餐饮", merchant = "星巴克").toQuery()
        assertEquals("餐饮", query?.categoryName)
        assertEquals("星巴克", query?.merchant)
    }

    // —— 从产物里认出 spec ——

    private fun artifacts(raw: String): JsonObject =
        Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `挂在 artifacts main 下也能认出来`() {
        val found = artifacts(
            """{"main":{"direction":"expense","from":"2026-10-01","to":"2026-10-31"}}""",
        ).ledgerQuerySpec()
        assertEquals(QueryDirection.EXPENSE, found?.toQuery()?.direction)
    }

    @Test
    fun `按节点分层的产物里同样能找到`() {
        val found = artifacts(
            """{"extract":{"amount":38.5},"write":{"direction":"income","merchant":"星巴克"}}""",
        ).ledgerQuerySpec()
        assertEquals(QueryDirection.INCOME, found?.toQuery()?.direction)
        assertEquals("星巴克", found?.toQuery()?.merchant)
    }

    @Test
    fun `没有 direction 的对象不会被误认成 spec`() {
        // 凭证那条产物有 entry_id 与 category，但没有 direction——误认会把一次查询
        // 建在一条记账产物上
        val found = artifacts(
            """{"main":{"entry_id":"task_x:main","category":"餐饮","amount":38.5}}""",
        ).ledgerQuerySpec()
        assertNull(found)
    }

    @Test
    fun `产物为空时安静地返回 null`() {
        assertNull(null.ledgerQuerySpec())
        assertNull(artifacts("{}").ledgerQuerySpec())
    }
}
