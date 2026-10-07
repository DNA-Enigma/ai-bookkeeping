package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 陌生商户的挑选与归类名到账户的映射。
 *
 * 这两步错了都不会报错，只会**静默地做错事**：把已经在账本里的商户当成陌生的
 * 去花钱调模型，或者把「餐饮」对到一个同名的收入分类上。所以两条边界都要钉住。
 */
class MerchantCategorizationTest {

    private fun row(
        merchant: String?,
        direction: StatementDirection = StatementDirection.EXPENSE,
        rowNumber: Int = 1,
    ) = StatementRow(
        externalSource = "wechat",
        externalRef = "ref-$rowNumber",
        dateEpochDay = 20_000,
        amount = Money.parse("38.50", "CNY"),
        direction = direction,
        merchant = merchant,
        description = "拿铁 大杯",
        status = "支付成功",
        rawType = "商户消费",
        rawTime = "2026-09-15 08:12:33",
        rowNumber = rowNumber,
    )

    private fun planned(
        merchant: String?,
        known: Boolean = false,
        direction: StatementDirection = StatementDirection.EXPENSE,
        rowNumber: Int = 1,
    ) = PlannedEntry(
        row = row(merchant, direction, rowNumber),
        accountId = "asset.wechat",
        categoryId = if (known) "expense.food" else "expense.other",
        categoryName = if (known) "餐饮" else "其他支出",
        categoryFromHistory = known,
        duplicateCandidates = emptyList(),
    )

    private fun plan(
        entries: List<PlannedEntry> = emptyList(),
        duplicates: List<PlannedEntry> = emptyList(),
    ) = ImportPlan(
        formatId = "wechat",
        formatName = "微信支付账单",
        warnings = emptyList(),
        entries = entries,
        duplicates = duplicates,
        alreadyImportedCount = 0,
        statusChanged = emptyList(),
        excluded = emptyList(),
        unusable = emptyList(),
    )

    // —— 谁是「陌生商户」 ——

    @Test
    fun `只收落到了兜底分类的那些商户`() {
        val found = plan(
            entries = listOf(
                planned("星巴克", known = true, rowNumber = 1),
                planned("滴滴出行", known = false, rowNumber = 2),
            ),
        ).unknownMerchants()

        assertEquals(listOf("滴滴出行"), found.map { it.payee })
    }

    @Test
    fun `同一商户出现多次只出一条，带出现次数`() {
        // 用户是按商户做决定的：「星巴克出现 3 次」比三条一模一样的建议有用
        val found = plan(
            entries = listOf(
                planned("星巴克", rowNumber = 1),
                planned("星巴克", rowNumber = 2),
                planned("滴滴出行", rowNumber = 3),
                planned("星巴克", rowNumber = 4),
            ),
        ).unknownMerchants()

        assertEquals(2, found.size)
        assertEquals("星巴克", found[0].payee)
        assertEquals(3, found[0].occurrences)
        assertEquals(1, found[1].occurrences)
    }

    @Test
    fun `疑似重复的行不算——它们默认不入库`() {
        // 为一批不在账本里的行花钱调模型，是白花
        val found = plan(duplicates = listOf(planned("星巴克"))).unknownMerchants()
        assertTrue(found.isEmpty())
    }

    @Test
    fun `没有商户名的行不算陌生商户`() {
        val found = plan(
            entries = listOf(
                planned(null, rowNumber = 1),
                planned("   ", rowNumber = 2),
            ),
        ).unknownMerchants()
        assertTrue(found.isEmpty())
    }

    @Test
    fun `没有陌生商户时返回空表`() {
        assertTrue(plan(entries = listOf(planned("星巴克", known = true))).unknownMerchants().isEmpty())
    }

    @Test
    fun `商户名两边的空格被去掉，不会把同一家拆成两条`() {
        val found = plan(
            entries = listOf(planned(" 星巴克 ", rowNumber = 1), planned("星巴克", rowNumber = 2)),
        ).unknownMerchants()
        assertEquals(1, found.size)
        assertEquals(2, found[0].occurrences)
    }

    // —— 分类名 → 科目表里的账户 ——

    private fun account(id: String, name: String, type: AccountType) =
        AccountEntity(id = id, name = name, type = type, currency = "CNY")

    private val categories = listOf(
        account("expense.food", "餐饮", AccountType.EXPENSE),
        account("expense.transport", "交通", AccountType.EXPENSE),
        account("income.other", "其他收入", AccountType.INCOME),
    )

    private fun suggestion(name: String?, direction: StatementDirection = StatementDirection.EXPENSE) =
        MerchantSuggestion(payee = "星巴克", direction = direction, categoryName = name)

    @Test
    fun `名字对得上就落到那个账户上`() {
        val resolved = listOf(suggestion("餐饮")).resolveCategories(categories).single()
        assertEquals("expense.food", resolved.categoryId)
        assertTrue(resolved.resolved)
    }

    @Test
    fun `同名时优先取与收支方向同类的账户`() {
        // 收入与支出分类同名是可能的，方向是更可靠的线索
        val ambiguous = listOf(
            account("income.x", "餐饮", AccountType.INCOME),
            account("expense.x", "餐饮", AccountType.EXPENSE),
        )
        val expense = listOf(suggestion("餐饮", StatementDirection.EXPENSE))
            .resolveCategories(ambiguous).single()
        assertEquals("expense.x", expense.categoryId)

        val income = listOf(suggestion("餐饮", StatementDirection.INCOME))
            .resolveCategories(ambiguous).single()
        assertEquals("income.x", income.categoryId)
    }

    @Test
    fun `名字对不上科目表时留空，不猜一个近似的`() {
        val resolved = listOf(suggestion("宠物")).resolveCategories(categories).single()
        assertNull(resolved.categoryId)
        assertTrue(!resolved.resolved)
    }

    @Test
    fun `调度层没给这个名字时同样留空`() {
        assertNull(listOf(suggestion(null)).resolveCategories(categories).single().categoryId)
        assertNull(listOf(suggestion("   ")).resolveCategories(categories).single().categoryId)
    }
}
