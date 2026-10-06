package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalDraftTest {

    private val cny = "CNY"
    private fun money(text: String) = Money.parse(text, cny)

    @Test
    fun `平衡的支出凭证可以构造`() {
        val draft = JournalDraft.expense(
            dateEpochDay = 20_000,
            amount = money("38.00"),
            fromAccountId = "asset.alipay",
            categoryAccountId = "expense.food",
        )
        assertEquals(2, draft.postings.size)
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
        assertEquals(cny, draft.currency)
    }

    @Test
    fun `支出时资产侧为负、分类侧为正`() {
        val draft = JournalDraft.expense(
            dateEpochDay = 20_000,
            amount = money("38.00"),
            fromAccountId = "asset.alipay",
            categoryAccountId = "expense.food",
        )
        val assetSide = draft.postings.single { it.accountId == "asset.alipay" }
        val categorySide = draft.postings.single { it.accountId == "expense.food" }
        assertEquals(-3800L, assetSide.amount.amountMinor)
        assertEquals(3800L, categorySide.amount.amountMinor)
    }

    @Test
    fun `收入时资产侧为正、分类侧为负`() {
        val draft = JournalDraft.income(
            dateEpochDay = 20_000,
            amount = money("12000.00"),
            toAccountId = "asset.bank",
            categoryAccountId = "income.salary",
        )
        val assetSide = draft.postings.single { it.accountId == "asset.bank" }
        val incomeSide = draft.postings.single { it.accountId == "income.salary" }
        assertEquals(1_200_000L, assetSide.amount.amountMinor)
        assertEquals(-1_200_000L, incomeSide.amount.amountMinor)
    }

    @Test
    fun `转账两侧都是资产，不产生收支`() {
        val draft = JournalDraft.transfer(
            dateEpochDay = 20_000,
            amount = money("500.00"),
            fromAccountId = "asset.bank",
            toAccountId = "asset.alipay",
        )
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
        // 转账不涉及 EXPENSE / INCOME 账户，所以月度收支统计里不会出现它
        assertTrue(draft.postings.none { it.accountId.startsWith("expense.") })
        assertTrue(draft.postings.none { it.accountId.startsWith("income.") })
    }

    @Test
    fun `金额不平衡时拒绝构造`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JournalDraft(
                dateEpochDay = 20_000,
                payee = null,
                note = null,
                source = JournalSource.MANUAL,
                postings = listOf(
                    PostingDraft("asset.alipay", money("-38.00")),
                    PostingDraft("expense.food", money("30.00")),
                ),
            )
        }
        assertTrue("错误信息应说明差额，实际为 ${error.message}", error.message!!.contains("-800"))
    }

    @Test
    fun `只有一条分录时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft(
                dateEpochDay = 20_000,
                payee = null,
                note = null,
                source = JournalSource.MANUAL,
                postings = listOf(PostingDraft("asset.alipay", money("0.00"))),
            )
        }
    }

    @Test
    fun `币种不一致时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft(
                dateEpochDay = 20_000,
                payee = null,
                note = null,
                source = JournalSource.MANUAL,
                postings = listOf(
                    PostingDraft("asset.alipay", Money.parse("-38.00", "CNY")),
                    PostingDraft("expense.food", Money.parse("38.00", "USD")),
                ),
            )
        }
    }

    @Test
    fun `账户为空时拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.expense(
                dateEpochDay = 20_000,
                amount = money("38.00"),
                fromAccountId = "",
                categoryAccountId = "expense.food",
            )
        }
    }
}
