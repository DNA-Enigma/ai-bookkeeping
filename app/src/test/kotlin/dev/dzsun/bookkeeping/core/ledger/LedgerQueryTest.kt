package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问账里那几个**纯函数**：商户名的 LIKE 转义、方向的符号折算、构造期的拒绝。
 *
 * 不测 SQL（端上不开库，没有 Robolectric）——聚合语句的口径由
 * `tools/verify-report-sql.py` 那一路在构建期对着真 SQLite 验。
 */
class LedgerQueryTest {

    // —— LIKE 通配符转义 ——

    @Test
    fun `百分号与下划线被转义，不当作通配符`() {
        // 不转义的话「50%折扣店」会变成「50 后面随便什么」，静默多算一堆别家的账
        assertEquals("%50\\%折扣店%", likePattern("50%折扣店"))
        assertEquals("%a\\_b%", likePattern("a_b"))
    }

    @Test
    fun `反斜杠自身先转，否则会把转义符吃掉`() {
        // "a\%b" 若不先转反斜杠，会变成 a + 转义 + 通配符，两侧都错
        assertEquals("%a\\\\\\%b%", likePattern("a\\%b"))
    }

    @Test
    fun `普通名字两侧加通配符做包含匹配`() {
        assertEquals("%星巴克%", likePattern("星巴克"))
    }

    // —— 方向的符号折算 ——

    @Test
    fun `收入侧的负号要翻回来`() {
        // 分录符号约定：支出分类记正、收入分类记负。SQL 求和拿到的是负数，
        // 不翻的话「这个月挣了多少」会答成 -500.00
        assertEquals(500_00L, applyDirection(-500_00L, QueryDirection.INCOME))
        // 支出侧本来就记正，原样带过
        assertEquals(500_00L, applyDirection(500_00L, QueryDirection.EXPENSE))
    }

    @Test
    fun `两侧一起时不翻，得到的是净支出`() {
        assertEquals(300_00L, applyDirection(300_00L, QueryDirection.BOTH))
    }

    // —— 方向 → 会计类型 ——

    @Test
    fun `转账两侧都是资产，任何方向都落不进筛选条件`() {
        assertTrue(QueryDirection.EXPENSE.accountTypes.none { it == AccountType.ASSET })
        assertEquals(listOf(AccountType.EXPENSE, AccountType.INCOME), QueryDirection.BOTH.accountTypes)
    }

    // —— 构造期的拒绝 ——

    @Test(expected = IllegalArgumentException::class)
    fun `起点晚于终点时拒绝构造`() {
        LedgerQuery(fromEpochDay = 20_000, toEpochDay = 19_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `分组上限必须为正`() {
        LedgerQuery(limit = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空串商户名被拒绝`() {
        // 空串的 LIKE '%%' 会匹配全部，看起来像「答了」其实答错了问题
        LedgerQuery(merchant = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空串分类名被拒绝`() {
        LedgerQuery(categoryName = "")
    }

    @Test
    fun `两端都不给日期是合法的——「一共花了多少」`() {
        val query = LedgerQuery()
        assertEquals(null, query.fromEpochDay)
        assertEquals(null, query.toEpochDay)
    }
}
