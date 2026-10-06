package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.ledger.ItemDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemLinesTest {

    @Test
    fun `blank description rows are dropped`() {
        val drafts = listOf(
            ItemLine("拿铁 大杯", "38.00"),
            ItemLine("   ", "1.00"),
            ItemLine("", ""),
            ItemLine("纸杯蛋糕", ""),
        ).toItemDrafts("CNY")
        assertEquals(
            listOf(
                ItemDraft("拿铁 大杯", 3_800L),
                ItemDraft("纸杯蛋糕", null),
            ),
            drafts,
        )
    }

    @Test
    fun `missing amount stays null - many receipts only print a total`() {
        val drafts = listOf(ItemLine("  拿铁 大杯  ", "  ")).toItemDrafts("CNY")
        assertEquals(ItemDraft("拿铁 大杯", null), drafts.single())
    }

    @Test
    fun `item amounts are not required to sum to the entry total`() {
        // 折扣、税、抹零都会让明细之和 ≠ 总额。这里刻意不校验——
        // 强行对齐会造出错账，而明细只描述「买了什么」。
        val items = listOf(
            ItemLine("拿铁 大杯", "38.50"),
            ItemLine("纸杯蛋糕", "28.00"),
        ).toItemDrafts("CNY")
        val itemSum = items.sumOf { it.amountMinor ?: 0L }
        // 总额 3850 ≠ 明细之和 6650，照样可以进 JournalDraft
        val draft = dev.dzsun.bookkeeping.core.ledger.JournalDraft.expense(
            dateEpochDay = 20_000,
            amount = dev.dzsun.bookkeeping.core.money.Money.parse("38.50", "CNY"),
            fromAccountId = "asset.alipay",
            categoryAccountId = "expense.food",
        ).copy(items = items)
        assertEquals(2, draft.items.size)
        assertEquals(6_650L, itemSum)
        // 分录自己平衡；明细之和与它无关
        assertEquals(0L, draft.postings.sumOf { it.amount.amountMinor })
    }

    @Test
    fun `empty list converts to empty drafts`() {
        assertTrue(emptyList<ItemLine>().toItemDrafts("CNY").isEmpty())
    }
}
