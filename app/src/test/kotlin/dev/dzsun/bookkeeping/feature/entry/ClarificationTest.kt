package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.network.FieldEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationTest {

    private fun card(
        kind: EntryKind = EntryKind.EXPENSE,
        amount: String = "30",
        categoryId: String = "expense.food",
        payee: String = "星巴克",
        note: String = "打车",
        date: Long = 100L,
        confidence: Float = 0.9f,
    ) = DraftCard(
        id = "c1",
        kind = kind,
        amountText = amount,
        categoryId = categoryId,
        payee = payee,
        note = note,
        dateEpochDay = date,
        confidence = confidence,
    )

    @Test
    fun `unchanged card produces no edits`() {
        val baseline = card()
        assertTrue(collectFieldEdits(baseline, card()).isEmpty())
    }

    @Test
    fun `each touched field yields one FieldEdit with from and to`() {
        val baseline = card()
        val current = card(
            kind = EntryKind.INCOME,
            amount = "31",
            categoryId = "income.salary",
            payee = "星巴克臻选",
            note = "打车回家",
            date = 101L,
        )
        val edits = collectFieldEdits(baseline, current)
        assertEquals(6, edits.size)
        assertEquals(FieldEdit(EditFields.KIND, "EXPENSE", "INCOME"), edits.first { it.field == EditFields.KIND })
        assertEquals(FieldEdit(EditFields.AMOUNT, "30", "31"), edits.first { it.field == EditFields.AMOUNT })
        assertEquals(FieldEdit(EditFields.DATE, "100", "101"), edits.first { it.field == EditFields.DATE })
    }

    @Test
    fun `local clarification only for low confidence cards`() {
        assertNull(localClarification(listOf(card(confidence = 0.9f))))
        val pending = localClarification(listOf(card(confidence = 0.5f)))
        assertNotNull(pending)
        assertEquals(true, pending!!.blocking)
        assertEquals(2, pending.options.size)
        assertEquals("expense", pending.options[0].id)
        assertEquals("income", pending.options[1].id)
    }

    @Test
    fun `option id maps back to entry kind`() {
        assertEquals(EntryKind.EXPENSE, kindFromOptionId("expense"))
        assertEquals(EntryKind.INCOME, kindFromOptionId("income"))
        assertNull(kindFromOptionId("whatever"))
    }
}
