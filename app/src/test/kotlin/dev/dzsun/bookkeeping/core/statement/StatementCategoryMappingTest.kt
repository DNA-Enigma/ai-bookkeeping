package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单自带的「交易分类」列 → 本账本分类。
 *
 * 实测支付宝的「交易分类」列写着「餐饮美食」「交通出行」这些**正确的分类**，
 * 但此前只被用于排除与退款判断，从没用来定分类，于是整批落到「其他支出」。
 *
 * 真实取值取自 2026-10-06 导出的支付宝账单（596 笔）。
 */
class StatementCategoryMappingTest {

    /** 本账本科目表的收支分类——与 `assets/default_accounts.json` 一致。 */
    private fun accounts(): List<AccountEntity> = listOf(
        account("expense.food", "餐饮", AccountType.EXPENSE),
        account("expense.transport", "交通", AccountType.EXPENSE),
        account("expense.shopping", "购物", AccountType.EXPENSE),
        account("expense.housing", "居住", AccountType.EXPENSE),
        account("expense.communication", "通讯", AccountType.EXPENSE),
        account("expense.entertainment", "娱乐", AccountType.EXPENSE),
        account("expense.medical", "医疗", AccountType.EXPENSE),
        account("expense.education", "教育", AccountType.EXPENSE),
        account("expense.other", "其他支出", AccountType.EXPENSE),
        account("income.salary", "工资", AccountType.INCOME),
        account("income.bonus", "奖金", AccountType.INCOME),
        account("income.investment", "投资收益", AccountType.INCOME),
        account("income.other", "其他收入", AccountType.INCOME),
    )

    private fun account(id: String, name: String, type: AccountType) =
        AccountEntity(id = id, name = name, type = type, currency = "CNY")

    /** 直接读真实的 assets 文件，保证测的是**实际生效的那份配置**。 */
    private val rules: List<StatementCategoryRule> = run {
        val file = File("src/main/assets/statement_categories.json")
        assertTrue("找不到分类映射配置：${file.absolutePath}", file.isFile)
        Json { ignoreUnknownKeys = true }
            .decodeFromString<StatementCategoryFile>(file.readText()).rules
    }

    private fun match(rawType: String?, direction: StatementDirection = StatementDirection.EXPENSE) =
        matchStatementCategory(rawType, direction, rules, accounts())

    // —— 真实账单里出现的每一个消费分类 ——

    @Test
    fun `餐饮美食归到餐饮`() {
        // 实测 217 笔，最高频的一类
        assertEquals("expense.food", match("餐饮美食")?.id)
    }

    @Test
    fun `交通出行归到交通`() {
        // 实测 194 笔
        assertEquals("expense.transport", match("交通出行")?.id)
    }

    @Test
    fun `日用百货归到购物`() {
        // 实测 36 笔
        assertEquals("expense.shopping", match("日用百货")?.id)
    }

    @Test
    fun `文化休闲归到娱乐`() {
        // 实测 21 笔
        assertEquals("expense.entertainment", match("文化休闲")?.id)
    }

    @Test
    fun `生活服务归到居住`() {
        // 实测 12 笔
        assertEquals("expense.housing", match("生活服务")?.id)
    }

    @Test
    fun `数码电器归到购物`() {
        // 实测 8 笔
        assertEquals("expense.shopping", match("数码电器")?.id)
    }

    @Test
    fun `服饰装扮归到购物`() {
        // 实测 4 笔
        assertEquals("expense.shopping", match("服饰装扮")?.id)
    }

    @Test
    fun `爱车养车归到交通`() {
        // 实测 3 笔
        assertEquals("expense.transport", match("爱车养车")?.id)
    }

    @Test
    fun `家居家装归到居住`() {
        // 实测 2 笔
        assertEquals("expense.housing", match("家居家装")?.id)
    }

    @Test
    fun `医疗健康归到医疗`() {
        // 实测 1 笔
        assertEquals("expense.medical", match("医疗健康")?.id)
    }

    // —— 认不出的必须落到兜底，不能瞎猜 ——

    @Test
    fun `资金转移这类词映射不上`() {
        assertNull(match("资金转移"))
    }

    @Test
    fun `商业服务语义模糊_不猜`() {
        // 实测 15 笔，但它在账本里没有明确对口分类——猜成购物或居住都是错的
        assertNull(match("商业服务"))
    }

    @Test
    fun `美容美发没有对口分类_不猜`() {
        assertNull(match("美容美发"))
    }

    @Test
    fun `充值缴费既可能是通讯也可能是居住_不猜`() {
        assertNull(match("充值缴费"))
    }

    @Test
    fun `陌生新词映射不上`() {
        assertNull(match("某个从没见过的分类"))
    }

    @Test
    fun `空值与空串映射不上`() {
        assertNull(match(null))
        assertNull(match("   "))
    }

    // —— 方向约束 ——

    @Test
    fun `支出分类不会映射到收入行上`() {
        // 「餐饮」是支出分类；一笔写着「餐饮美食」的收入行不该被归到它
        assertNull(match("餐饮美食", StatementDirection.INCOME))
    }

    @Test
    fun `收入分类映射到收入行`() {
        assertEquals("income.salary", match("工资", StatementDirection.INCOME)?.id)
        assertEquals("income.investment", match("投资收益", StatementDirection.INCOME)?.id)
    }

    @Test
    fun `不计收支的行不参与映射`() {
        assertNull(match("餐饮美食", StatementDirection.NEUTRAL))
    }

    // —— 账户名对不上时落空 ——

    @Test
    fun `账本里没有这个分类名时映射结果为空`() {
        // 用户把「娱乐」改名或删掉之后，映射必须落空交给兜底，而不是指向不存在的账户
        val withoutEntertainment = accounts().filterNot { it.name == "娱乐" }
        assertNull(matchStatementCategory("文化休闲", StatementDirection.EXPENSE, rules, withoutEntertainment))
    }

    @Test
    fun `别名是包含匹配_账单取值比配置更长也能命中`() {
        // 配置里写「交通」，账单给的是「交通出行」
        val shortRule = listOf(StatementCategoryRule(category = "交通", aliases = listOf("交通")))
        val hit = matchStatementCategory("交通出行", StatementDirection.EXPENSE, shortRule, accounts())
        assertEquals("expense.transport", hit?.id)
    }

    @Test
    fun `每条规则都指向账本里真实存在的分类名`() {
        // 写错一个名字，那条规则就永远静默失效——这条测试专门盯它
        val names = accounts().map { it.name }.toSet()
        val missing = rules.map { it.category }.filterNot { it in names }
        assertTrue("这些分类名在科目表里不存在：$missing", missing.isEmpty())
    }

    @Test
    fun `没有一条规则是空的`() {
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.all { it.aliases.any { a -> a.isNotBlank() } })
        assertFalse(rules.any { it.category.isBlank() })
    }
}
