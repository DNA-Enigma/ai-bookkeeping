package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.ledger.ConfidenceGate
import dev.dzsun.bookkeeping.core.network.ReceiptFields
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面侧的 P0-a 判定：高置信直入账、低置信走确认卡、**拿不到也走确认卡**。
 *
 * 判定规则本身在数据层的 [ConfidenceGate]（那边有自己的测试）。这里测的是
 * **界面有没有老老实实用它**——两处各判一次，迟早会出现「确认页说要确认、
 * 入账却已经自动记了」这种自相矛盾的状态。
 */
class AutoConfirmTest {

    private val threshold = ConfidenceGate.DEFAULT_THRESHOLD

    private fun card(confidence: Float?) = DraftCard(
        id = "c1",
        kind = EntryKind.EXPENSE,
        amountText = "38.50",
        categoryId = "expense.food",
        payee = "星巴克",
        note = "",
        dateEpochDay = 100L,
        confidence = confidence,
    )

    // ------------------------------------------------------------ 三态

    @Test
    fun `达阈值直接入账`() {
        assertTrue(card(threshold).isHighConfidence(threshold))
        assertTrue(card(0.98f).isHighConfidence(threshold))
    }

    @Test
    fun `低于阈值走确认卡`() {
        assertFalse(card(threshold - 0.01f).isHighConfidence(threshold))
        assertFalse(card(0.3f).isHighConfidence(threshold))
    }

    @Test
    fun `拿不到置信度不算高置信`() {
        // 这是这块最要紧的一条。null 的三种来路都不是异常：老服务端不给、
        // 纯文本单步路由的产物里没有这个字段、事件流断了客户端只回看到 LedgerEntry。
        // 把 null 当成可信，等于在这些路上**静默跳过用户确认**——
        // 而那恰恰是最该让人看一眼的情形。
        assertFalse(card(null).isHighConfidence(threshold))
    }

    // ------------------------------------------------------------ 阈值可调

    @Test
    fun `同一笔在不同阈值下结论可以相反`() {
        // 设置页把阈值调到 0.9 之后，原来能直入账的 0.88 就该改成问一句
        val c = card(0.88f)
        assertTrue(c.isHighConfidence(0.85f))
        assertFalse(c.isHighConfidence(0.90f))
    }

    @Test
    fun `阈值拉到上限时几乎什么都不放行`() {
        val max = ConfidenceGate.MAX_THRESHOLD
        // `>=` 的语义下，恰好等于阈值仍然算达阈值，所以阈值 1.0 拦得住 0.99 及以下，
        // 拦不住「恰好 1.0」——而 extract 实测落在 0.9~0.98，碰不到那个点。
        // 这里按实际语义断言，不按注释里的「永不」断言：
        // 写成 assertFalse(card(1f)) 会是一条假的绿/红，因为它测的不是代码在做的事。
        assertFalse(card(0.99f).isHighConfidence(max))
        assertFalse(card(0.5f).isHighConfidence(max))
        assertTrue(card(1f).isHighConfidence(max))
    }

    @Test
    fun `阈值被夹进合法范围`() {
        assertEquals(ConfidenceGate.MAX_THRESHOLD, ConfidenceGate.clamp(5f))
        assertEquals(ConfidenceGate.MIN_THRESHOLD, ConfidenceGate.clamp(0f))
    }

    // ------------------------------------------------------------ 置信度的传递

    @Test
    fun `服务端没给置信度时卡片上是 null 而不是 0`() {
        // 补成 0 的话，「识别得勉强（0.3）」和「压根没给」就再也分不出来了，
        // 而这两种该给用户的提示是不一样的
        val draft = ReceiptFields(amount = JsonPrimitive("12.34"), currency = "CNY")
            .toDraftCard(
                id = "t1",
                todayEpochDay = 1L,
                expenseCategories = emptyList(),
                incomeCategories = emptyList(),
            )

        assertNull(draft.confidence)
        assertFalse(draft.isHighConfidence(threshold))
    }

    @Test
    fun `服务端给了置信度就原样传下来`() {
        val draft = ReceiptFields(
            amount = JsonPrimitive("12.34"),
            currency = "CNY",
            confidence = 0.95,
        ).toDraftCard(
            id = "t2",
            todayEpochDay = 1L,
            expenseCategories = emptyList(),
            incomeCategories = emptyList(),
        )

        assertEquals(0.95f, draft.confidence)
        assertTrue(draft.isHighConfidence(threshold))
    }

    // ------------------------------------------------------------ 与离线降级的一致性

    @Test
    fun `离线澄清用的是同一个阈值`() {
        // 两处阈值要是各定各的，就会出现「本地认为该问、自动入账却直接放了」
        assertNull(localClarification(listOf(card(0.9f)), threshold))
        assertTrue(localClarification(listOf(card(0.6f)), threshold) != null)
        // 拿不到置信度的卡片同样要问
        assertTrue(localClarification(listOf(card(null)), threshold) != null)
    }
}
