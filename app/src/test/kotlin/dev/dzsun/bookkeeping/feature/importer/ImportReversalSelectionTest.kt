package dev.dzsun.bookkeeping.feature.importer

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.statement.ImportPlan
import dev.dzsun.bookkeeping.core.statement.PlannedEntry
import dev.dzsun.bookkeeping.core.statement.PlannedReversal
import dev.dzsun.bookkeeping.core.statement.StatementDirection
import dev.dzsun.bookkeeping.core.statement.StatementRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退款冲减的**勾选入口**这一段：默认不勾、只冲勾了的、勾了之后支出真的被抵消且仍平。
 *
 * 涉及 Room 的落库（幂等、事务、先写原消费再写冲减）在 `StatementImporter` 与
 * `LedgerRepository.postReversal` 的注释里说明了不变量，要真库才能跑；
 * 这里只钉住**不碰数据库、又最容易错**的三件事——勾选过滤、余额方向、复式平衡。
 */
class ImportReversalSelectionTest {

    private fun row(
        amount: String = "38.50",
        status: String = "已全额退款",
        rowNumber: Int = 5,
    ) = StatementRow(
        externalSource = "wechat",
        externalRef = "ref-$rowNumber",
        dateEpochDay = 20_000,
        amount = Money.parse(amount, "CNY"),
        direction = StatementDirection.EXPENSE,
        merchant = "星巴克咖啡",
        description = "拿铁 大杯",
        status = status,
        rawType = "商户消费",
        rawTime = "2026-09-15 08:12:33",
        rowNumber = rowNumber,
        isRefund = true,
    )

    private fun chargeDraft(amount: String = "38.50") = JournalDraft.expense(
        dateEpochDay = 20_000,
        amount = Money.parse(amount, "CNY"),
        fromAccountId = "asset.wechat",
        categoryAccountId = "expense.food",
        payee = "星巴克咖啡",
        source = JournalSource.STATEMENT,
    )

    private fun reversal(
        rowNumber: Int,
        refundAmount: String = "38.50",
    ): PlannedReversal = PlannedReversal(
        row = row(
            status = if (refundAmount == "38.50") "已全额退款" else "已退款¥$refundAmount",
            rowNumber = rowNumber,
        ),
        existingJournalId = null,
        draft = JournalDraft.reversalOf(
            original = chargeDraft(),
            amount = if (refundAmount == "38.50") null else Money.parse(refundAmount, "CNY"),
        ),
        refundAmount = Money.parse(refundAmount, "CNY"),
        categoryName = "餐饮",
    )

    private fun plan(reversals: List<PlannedReversal> = emptyList()): ImportPlan = ImportPlan(
        formatId = "wechat",
        formatName = "微信支付账单",
        warnings = emptyList(),
        entries = emptyList(),
        duplicates = emptyList(),
        alreadyImportedCount = 0,
        statusChanged = reversals.map { it.row },
        reversals = reversals,
        excluded = emptyList(),
        unusable = emptyList(),
    )

    // —— 勾选过滤：默认不勾 = 行为不变 ——

    @Test
    fun `默认不勾选时一笔冲减都不落`() {
        // 这就是「未勾选行为不变」：勾选入口出现之前是怎么落的，现在还是怎么落
        val plan = plan(listOf(reversal(rowNumber = 5), reversal(rowNumber = 6)))
        assertTrue(plan.selectedReversals(emptySet()).isEmpty())
    }

    @Test
    fun `只落用户勾选的那一笔`() {
        val plan = plan(listOf(reversal(rowNumber = 5), reversal(rowNumber = 6)))
        val picked = plan.selectedReversals(setOf(6))

        assertEquals(1, picked.size)
        assertEquals(6, picked.single().row.rowNumber)
    }

    @Test
    fun `勾了多笔就落多笔`() {
        val plan = plan(listOf(reversal(rowNumber = 5), reversal(rowNumber = 6), reversal(rowNumber = 7)))
        assertEquals(
            listOf(5, 7),
            plan.selectedReversals(setOf(5, 7)).map { it.row.rowNumber },
        )
    }

    @Test
    fun `索引按行号对齐，预览勾的和落库冲的是同一笔`() {
        val plan = plan(listOf(reversal(rowNumber = 5), reversal(rowNumber = 6)))
        val index = plan.reversalsByRow()

        assertEquals(setOf(5, 6), index.keys)
        assertEquals(6, index.getValue(6).row.rowNumber)
    }

    @Test
    fun `认不出退款金额的状态变化行没有入库入口`() {
        // 状态变了但金额认不出来的行不进 reversals——不能猜成全额，
        // 所以连勾选框都没有，勾选集里给多少行号都冲不了它
        val noReversal = ImportPlan(
            formatId = "wechat",
            formatName = "微信支付账单",
            warnings = emptyList(),
            entries = emptyList(),
            duplicates = emptyList(),
            alreadyImportedCount = 0,
            statusChanged = listOf(row(status = "已退款", rowNumber = 5)),
            reversals = emptyList(),
            excluded = emptyList(),
            unusable = emptyList(),
        )

        assertTrue(noReversal.selectedReversals(setOf(5)).isEmpty())
        assertTrue(noReversal.reversalsByRow().isEmpty())
        // 预览里照旧是「需单独处理」，不会给出一个点了没用的勾选框
        val preview = noReversal.toPreviewRows().single()
        assertEquals(PreviewRow.Kind.STATUS_CHANGED, preview.kind)
        assertTrue(preview.skipReason!!.contains("状态变化"))
    }

    @Test
    fun `可冲减的状态变化行不再写需单独处理`() {
        val preview = plan(listOf(reversal(rowNumber = 5))).toPreviewRows().single()
        assertEquals(PreviewRow.Kind.STATUS_CHANGED, preview.kind)
        assertNull("能办的账不该再写「需单独处理」", preview.skipReason)
    }

    // —— 支出正确：冲减要把原消费恰好抵消，且复式仍平 ——

    private fun expenseAccountNet(drafts: List<JournalDraft>): Long =
        drafts.sumOf { draft ->
            draft.postings.filter { it.accountId == "expense.food" }.sumOf { it.amount.amountMinor }
        }

    @Test
    fun `全额退款冲减后这笔支出归零`() {
        val charge = chargeDraft()
        val full = reversal(rowNumber = 5)

        // 原支出 38.50，退款把分类那一侧原样取反——当月净支出回到 0
        assertEquals(3_850L, expenseAccountNet(listOf(charge)))
        assertEquals(0L, expenseAccountNet(listOf(charge, full.draft)))
    }

    @Test
    fun `部分退款只减掉退回的那部分`() {
        val charge = chargeDraft()
        val partial = reversal(rowNumber = 6, refundAmount = "6.60")

        // 38.50 - 6.60 = 31.90：退多少减多少，既不猜全额也不少记
        assertEquals(3_190L, expenseAccountNet(listOf(charge, partial.draft)))
    }

    @Test
    fun `冲减凭证构造即平，没有绕过复式校验`() {
        val draft = reversal(rowNumber = 5).draft
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
    }

    @Test
    fun `部分冲减同样平`() {
        val draft = reversal(rowNumber = 6, refundAmount = "6.60").draft
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
        assertTrue("退回少于原消费就算部分", reversal(rowNumber = 6, refundAmount = "6.60").isPartial)
    }

    @Test
    fun `全额冲减不算部分退款`() {
        assertFalse(reversal(rowNumber = 5).isPartial)
    }

    // —— 待导入的账不受勾选影响 ——

    private fun entry(rowNumber: Int) = PlannedEntry(
        row = row(status = "支付成功", rowNumber = rowNumber),
        accountId = "asset.wechat",
        categoryId = "expense.other",
        categoryName = "其他支出",
        categoryFromHistory = false,
        duplicateCandidates = emptyList(),
    )

    @Test
    fun `勾选冲减不会把待导入的条目也算成冲减`() {
        val plan = ImportPlan(
            formatId = "wechat",
            formatName = "微信支付账单",
            warnings = emptyList(),
            entries = listOf(entry(rowNumber = 1)),
            duplicates = emptyList(),
            alreadyImportedCount = 0,
            statusChanged = listOf(row(rowNumber = 5)),
            reversals = listOf(reversal(rowNumber = 5)),
            excluded = emptyList(),
            unusable = emptyList(),
        )

        // 待导入仍是 1 笔；勾选只决定那 1 笔冲减落不落，不改变 entries 本身
        assertEquals(1, plan.entries.size)
        assertEquals(1, plan.selectedReversals(setOf(5)).size)
        assertTrue(plan.selectedReversals(emptySet()).isEmpty())
    }
}
