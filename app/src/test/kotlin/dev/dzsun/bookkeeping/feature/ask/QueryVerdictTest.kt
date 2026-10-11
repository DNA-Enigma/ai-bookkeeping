package dev.dzsun.bookkeeping.feature.ask

import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `【问账判定】` 的输出 → [QueryVerdict]。
 *
 * 纯函数，所以每一条映射规则都能逐条钉死——这一层要是悄悄映射错了，
 * 用户拿到的就是**另一个问题的答案**，而屏幕上没有任何东西提示他。
 */
class QueryVerdictTest {

    private val today = LocalDate.of(2026, 10, 7)
    private val categories = setOf("餐饮", "交通", "工资", "其他支出", "其他收入")

    private fun ledger(json: String) =
        parseQueryVerdict(json, today, categories) as QueryVerdict.Ledger

    @Test
    fun `最典型的问句`() {
        val q = ledger(
            """{"intent":"query","range":"this_month","direction":"expense",""" +
                """"subject":"","subject_kind":"none","group_by":"none"}""",
        ).query
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), q.fromEpochDay)
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), q.toEpochDay)
        assertEquals(QueryDirection.EXPENSE, q.direction)
        assertNull(q.categoryName)
        assertNull(q.merchant)
        assertEquals(QueryGrouping.NONE, q.grouping)
    }

    @Test
    fun `分类名必须真的在科目表里才认成分类`() {
        val q = ledger(
            """{"intent":"query","range":"this_month","direction":"expense",""" +
                """"subject":"餐饮","subject_kind":"category","group_by":"none"}""",
        ).query
        assertEquals("餐饮", q.categoryName)
        assertNull(q.merchant)
    }

    @Test
    fun `说是分类但名字不在科目表里，降级成商户`() {
        // 名字本身有用（「星巴克」当商户查照样查得到），丢掉它才是真的答不了
        val q = ledger(
            """{"intent":"query","range":"all","direction":"expense",""" +
                """"subject":"星巴克","subject_kind":"category","group_by":"none"}""",
        ).query
        assertEquals("星巴克", q.merchant)
        assertNull(q.categoryName)
    }

    @Test
    fun `商户就是商户`() {
        val q = ledger(
            """{"intent":"query","range":"all","direction":"expense",""" +
                """"subject":"星巴克","subject_kind":"merchant","group_by":"none"}""",
        ).query
        assertEquals("星巴克", q.merchant)
    }

    @Test
    fun `时间词出现在 subject 里不算商户名`() {
        // 拿「这个月」去做 LIKE 只会查出「没有记录」，看着像答了其实答错了
        val q = ledger(
            """{"intent":"query","range":"this_month","direction":"expense",""" +
                """"subject":"这个月","subject_kind":"merchant","group_by":"none"}""",
        ).query
        assertNull(q.merchant)
    }

    @Test
    fun `没提时间就是不设限`() {
        val q = ledger("""{"intent":"query","direction":"expense"}""").query
        assertNull(q.fromEpochDay)
        assertNull(q.toEpochDay)
    }

    @Test
    fun `收入与收支`() {
        assertEquals(QueryDirection.INCOME, ledger("""{"intent":"query","direction":"income"}""").query.direction)
        assertEquals(QueryDirection.BOTH, ledger("""{"intent":"query","direction":"both"}""").query.direction)
    }

    @Test
    fun `没提方向按支出——与本地词法的兜底同一口径`() {
        assertEquals(QueryDirection.EXPENSE, ledger("""{"intent":"query","range":"all"}""").query.direction)
    }

    @Test
    fun `分组四种取值`() {
        assertEquals(QueryGrouping.CATEGORY, ledger("""{"intent":"query","group_by":"category"}""").query.grouping)
        assertEquals(QueryGrouping.MERCHANT, ledger("""{"intent":"query","group_by":"merchant"}""").query.grouping)
        assertEquals(QueryGrouping.MONTH, ledger("""{"intent":"query","group_by":"month"}""").query.grouping)
        assertEquals(QueryGrouping.NONE, ledger("""{"intent":"query","group_by":"none"}""").query.grouping)
    }

    @Test
    fun `分组认不出来就不分组，不判不合法`() {
        // 分组只决定要不要多给一份明细，猜错顶多少一块，不会让总数变错
        assertEquals(QueryGrouping.NONE, ledger("""{"intent":"query","group_by":"周"}""").query.grouping)
    }

    @Test
    fun `闲聊判成 Chatter`() {
        assertEquals(QueryVerdict.Chatter, parseQueryVerdict("""{"intent":"chat"}""", today, categories))
    }

    @Test
    fun `围栏与前后杂音都容错`() {
        val out = "好的，这是判定：\n```json\n{\"intent\":\"query\",\"range\":\"today\"}\n```\n"
        assertEquals(
            LocalDate.of(2026, 10, 7).toEpochDay(),
            ledger(out).query.fromEpochDay,
        )
    }

    // —— 不合法输出 ——

    @Test
    fun `不是 JSON 就是不合法`() {
        val v = parseQueryVerdict("我觉得这是在问账本", today, categories)
        assertTrue(v is QueryVerdict.Invalid)
    }

    @Test
    fun `没给 intent 是不合法`() {
        assertTrue(parseQueryVerdict("""{"range":"this_month"}""", today, categories) is QueryVerdict.Invalid)
    }

    @Test
    fun `intent 认不出来是不合法`() {
        assertTrue(parseQueryVerdict("""{"intent":"maybe"}""", today, categories) is QueryVerdict.Invalid)
    }

    @Test
    fun `时间词认不出来是不合法，不静默当不限时间`() {
        // 静默当不限时间会把「这个月花了多少」答成一个有史以来的总数
        val v = parseQueryVerdict("""{"intent":"query","range":"last_3_months"}""", today, categories)
        assertTrue(v is QueryVerdict.Invalid)
        assertTrue((v as QueryVerdict.Invalid).reason.contains("last_3_months"))
    }

    @Test
    fun `方向认不出来是不合法`() {
        assertTrue(
            parseQueryVerdict("""{"intent":"query","direction":"sideways"}""", today, categories)
                is QueryVerdict.Invalid,
        )
    }
}
