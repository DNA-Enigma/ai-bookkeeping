package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测「流水行 → 凭证」这一段映射。
 *
 * 它脱离 Room 与 Android，所以能在纯 JVM 里跑；而它恰恰是最容易写错的地方——
 * 账户、分类、状态、外部字段、明细，哪一样错了都不会报错，只会在对账或
 * 几周后想不起买了什么的时候才暴露。
 */
class StatementImporterMappingTest {

    private fun row(
        direction: StatementDirection = StatementDirection.EXPENSE,
        amount: String = "38.50",
        description: String? = "拿铁 大杯",
        merchant: String? = "星巴克咖啡",
        status: String? = "支付成功",
        rawType: String? = "商户消费",
    ) = StatementRow(
        externalSource = "wechat",
        externalRef = "4200002319202609151234567890",
        dateEpochDay = 20_000,
        amount = Money.parse(amount, "CNY"),
        direction = direction,
        merchant = merchant,
        description = description,
        status = status,
        rawType = rawType,
        rawTime = "2026-09-15 08:12:33",
        rowNumber = 5,
    )

    private fun entry(
        row: StatementRow,
        categoryFromHistory: Boolean = true,
    ) = PlannedEntry(
        row = row,
        accountId = "asset.wechat",
        categoryId = if (categoryFromHistory) "expense.food" else "expense.other",
        categoryName = if (categoryFromHistory) "餐饮" else "其他支出",
        categoryFromHistory = categoryFromHistory,
        duplicateCandidates = emptyList(),
    )

    @Test
    fun `支出映射成资产减少、分类增加`() {
        val draft = toJournalDraft(entry(row()))
        val asset = draft.postings.single { it.accountId == "asset.wechat" }
        val category = draft.postings.single { it.accountId == "expense.food" }
        assertEquals(-38_50L, asset.amount.amountMinor)
        assertEquals(38_50L, category.amount.amountMinor)
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
    }

    @Test
    fun `收入映射成资产增加、分类减少`() {
        val draft = toJournalDraft(
            entry(row(direction = StatementDirection.INCOME, amount = "8000.00", description = null)),
        )
        val asset = draft.postings.single { it.accountId == "asset.wechat" }
        assertEquals(800_000L, asset.amount.amountMinor)
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
    }

    @Test
    fun `来源标成 STATEMENT 而不是手记或拍票`() {
        // 「这笔是银行来的还是我记的」决定界面怎么向用户交代来源
        assertEquals(JournalSource.STATEMENT, toJournalDraft(entry(row())).source)
    }

    @Test
    fun `外部流水号与状态带进凭证——去重和发现退款都靠它们`() {
        val draft = toJournalDraft(entry(row()))
        assertEquals("wechat", draft.externalSource)
        assertEquals("4200002319202609151234567890", draft.externalRef)
        assertEquals("支付成功", draft.externalStatus)
        assertTrue(draft.isImported)
    }

    @Test
    fun `流水里的商品说明落成明细——这就是"买了什么"`() {
        val draft = toJournalDraft(entry(row(description = "拿铁 大杯")))
        assertEquals(1, draft.items.size)
        assertEquals("拿铁 大杯", draft.items.single().description)
    }

    @Test
    fun `没有商品说明时明细为空而不是空串`() {
        // 空描述的明细行会被 JournalDraft 拒绝——空明细对「想起买了什么」毫无用处
        val draft = toJournalDraft(entry(row(description = null)))
        assertTrue(draft.items.isEmpty())
        assertTrue(toJournalDraft(entry(row(description = "   "))).items.isEmpty())
    }

    @Test
    fun `按历史归好类的置已核对`() {
        assertEquals(JournalStatus.CLEARED, toJournalDraft(entry(row(), categoryFromHistory = true)).status)
    }

    @Test
    fun `落在兜底分类的留待确认`() {
        // 兜底分类是猜的，值得回头看一眼；按历史归好类的没必要再看第二遍
        assertEquals(JournalStatus.PENDING, toJournalDraft(entry(row(), categoryFromHistory = false)).status)
    }

    @Test
    fun `商户与商品分别落在收款方与备注`() {
        val draft = toJournalDraft(entry(row()))
        assertEquals("星巴克咖啡", draft.payee)
        assertEquals("拿铁 大杯", draft.note)
    }

    // —— 账单两列都没说清时，摘要用账单自带的「交易分类」 ——

    @Test
    fun `商户截断且描述是平台话术时_摘要写成交易分类`() {
        // 实测：交易分类=餐饮美食 | 交易对方=飞 | 商品说明=收钱码收款 | 9.50
        // 照抄「收钱码收款」等于把一句废话写进账本，事后翻到还是不知道买了什么
        val draft = toJournalDraft(
            entry(row(merchant = "飞", description = "收钱码收款", rawType = "餐饮美食")),
        )
        assertEquals("餐饮美食", draft.note)
    }

    @Test
    fun `商户有信息时摘要仍是商品说明_不许被分类顶掉`() {
        // 实测这类有 140 笔，是最不能误伤的一档
        val draft = toJournalDraft(
            entry(row(merchant = "小东北麻辣烫", description = "收钱码收款", rawType = "餐饮美食")),
        )
        assertEquals("收钱码收款", draft.note)
    }

    @Test
    fun `收款方始终是商户_不因为摘要改成分类就跟着变`() {
        val draft = toJournalDraft(
            entry(row(merchant = "飞", description = "收钱码收款", rawType = "餐饮美食")),
        )
        assertEquals("飞", draft.payee)
    }
}
