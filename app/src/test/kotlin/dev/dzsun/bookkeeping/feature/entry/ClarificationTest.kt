package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.network.ClarificationOption
import dev.dzsun.bookkeeping.core.network.FieldEdit
import dev.dzsun.bookkeeping.core.ledger.ConfidenceGate
import dev.dzsun.bookkeeping.core.network.ReceiptFields
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationTest {

    /** 与设置页默认值一致。测的是「阈值可传」，所以这里取一个明确的数。 */
    private val THRESHOLD = ConfidenceGate.DEFAULT_THRESHOLD

    private fun card(
        kind: EntryKind = EntryKind.EXPENSE,
        amount: String = "30",
        categoryId: String = "expense.food",
        payee: String = "星巴克",
        note: String = "打车",
        date: Long = 100L,
        confidence: Float = 0.9f,
        place: String = "",
        items: List<ItemLine> = emptyList(),
    ) = DraftCard(
        id = "c1",
        kind = kind,
        amountText = amount,
        categoryId = categoryId,
        payee = payee,
        note = note,
        dateEpochDay = date,
        confidence = confidence,
        place = place,
        items = items,
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
    fun `place and items edits are reported as their own fields`() {
        val baseline = card(
            place = "国贸",
            items = listOf(ItemLine("拿铁 大杯", "38.00")),
        )
        val current = card(
            place = "公司楼下",
            items = listOf(ItemLine("拿铁 大杯", "38.00"), ItemLine("纸杯蛋糕")),
        )
        val edits = collectFieldEdits(baseline, current)
        assertEquals(2, edits.size)
        assertEquals(FieldEdit(EditFields.PLACE, "国贸", "公司楼下"), edits.first { it.field == EditFields.PLACE })
        assertEquals(
            FieldEdit(EditFields.ITEMS, "拿铁 大杯:38.00", "拿铁 大杯:38.00|纸杯蛋糕:"),
            edits.first { it.field == EditFields.ITEMS },
        )
    }

    @Test
    fun `local clarification only for low confidence cards`() {
        assertNull(localClarification(listOf(card(confidence = 0.9f)), THRESHOLD))
        val pending = localClarification(listOf(card(confidence = 0.5f)), THRESHOLD)
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

    @Test
    fun `receipt fields map to draft card without touching floats`() {
        val fields = ReceiptFields(
            amount = JsonPrimitive("12.34"),
            currency = "CNY",
            merchant = "星巴克",
            direction = "income",
            confidence = 0.95,
            notes = "拿铁",
            datetime = "2026-10-06T12:00:00",
        )
        val draft = fields.toDraftCard(
            id = "t1",
            todayEpochDay = 999L,
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
        )
        assertEquals(EntryKind.INCOME, draft.kind)
        assertEquals("12.34", draft.amountText)
        assertEquals("星巴克", draft.payee)
        assertEquals(0.95f, draft.confidence)
        assertTrue(draft.isHighConfidence(THRESHOLD))
    }

    @Test
    fun `failed extraction falls back to today and empty amount`() {
        val fields = ReceiptFields(amount = null, currency = null, confidence = 0.2)
        val draft = fields.toDraftCard(
            id = "t2",
            todayEpochDay = 42L,
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
        )
        assertEquals(EntryKind.EXPENSE, draft.kind)
        assertEquals("", draft.amountText)
        assertEquals(42L, draft.dateEpochDay)
        assertTrue(!draft.isHighConfidence(THRESHOLD))
    }

    // —— 无选项澄清：自由文本是契约里唯一可用的答复路径 ——

    private val options = listOf(
        ClarificationOption("expense", "支出"),
        ClarificationOption("income", "收入"),
    )

    @Test
    fun `empty options require free text`() {
        assertFalse(canAnswerClarification(emptyList(), "", ""))
        assertFalse(canAnswerClarification(emptyList(), "", "   "))
        assertTrue(canAnswerClarification(emptyList(), "", "38 元，微信"))
    }

    @Test
    fun `options present require a selection not free text`() {
        assertTrue(canAnswerClarification(options, "expense", ""))
        assertFalse(canAnswerClarification(options, "", "随便写点什么"))
    }

    @Test
    fun `free text answer carries free_text and no answer_id`() {
        val answer = buildClarificationAnswer("q1", optionId = null, freeText = "  38 元，微信  ")
        assertNotNull(answer)
        assertNull(answer!!.answerId)
        assertEquals("38 元，微信", answer.freeText)
    }

    @Test
    fun `option answer carries answer_id and neither is blank returns null`() {
        val answer = buildClarificationAnswer("q1", optionId = "expense", freeText = null)
        assertEquals("expense", answer!!.answerId)
        assertNull(answer.freeText)
        assertNull(buildClarificationAnswer("q1", optionId = null, freeText = null))
        assertNull(buildClarificationAnswer("q1", optionId = "  ", freeText = " "))
    }
}
