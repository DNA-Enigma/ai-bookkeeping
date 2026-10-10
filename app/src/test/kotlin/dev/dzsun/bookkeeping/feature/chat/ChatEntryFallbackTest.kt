package dev.dzsun.bookkeeping.feature.chat

import dev.dzsun.bookkeeping.feature.entry.EntryKind
import dev.dzsun.bookkeeping.feature.entry.ParsedEntry as EntryDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话保底出卡（[withRulesEntryFallback]）。
 *
 * 背景：对话模式下模型收到「午饭花了 35」6/6 次只回了一句话（甚至回「查账」），
 * 不出卡 → 账记不上。这一层保证**不管模型守不守提示词**，只要本地规则解析得出
 * 合法条目（金额 > 0、分类在科目表里），用户就能看到确认卡。
 */
class ChatEntryFallbackTest {

    private val input = "午饭花了 35"

    private fun draft(
        amount: String = "35",
        category: String = "餐饮",
        kind: EntryKind = EntryKind.EXPENSE,
    ) = EntryDraft(
        id = "t-1",
        kind = kind,
        amountText = amount,
        categoryName = category,
        payee = "",
        note = "午饭",
    )

    /** 解析器被调用的次数 —— 「模型给了数组就不走兜底」要靠它断言。 */
    private var parseCalls = 0

    private fun recorder(
        parsed: List<EntryDraft>,
        expect: String = input,
    ): suspend (String) -> List<EntryDraft> = { text ->
        parseCalls++
        assertEquals("兜底拿的是用户原话", expect, text)
        parsed
    }

    private val knownCategory: (EntryDraft) -> Boolean =
        { it.categoryName in listOf("餐饮", "交通", "其他支出", "其他收入") }

    @Test
    fun `模型只回一句话且输入可解析 → 回复上挂出卡片`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = AzhangTurn.Reply("可以基于这笔午饭消费…"),
            parse = recorder(listOf(draft())),
            isKnownCategory = knownCategory,
        )

        assertTrue(turn is AzhangTurn.Reply)
        turn as AzhangTurn.Reply
        assertEquals("模型那句话要原样保留", "可以基于这笔午饭消费…", turn.body)
        assertEquals(1, turn.entries.size)
        assertEquals("35", turn.entries.first().amountText)
        assertEquals("餐饮", turn.entries.first().categoryName)
    }

    @Test
    fun `模型回「查账」信号时同样出卡——那正是最容易漏的一种`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = chatQueryReply(),
            parse = recorder(listOf(draft())),
            isKnownCategory = knownCategory,
        )

        assertTrue(turn is AzhangTurn.Reply)
        turn as AzhangTurn.Reply
        assertTrue(turn.isLedgerQuery)
        assertEquals(1, turn.entries.size)
    }

    @Test
    fun `模型已经给了数组 → 一次都不调用规则解析`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = AzhangTurn.Entry(listOf(draft())),
            parse = recorder(listOf(draft())),
            isKnownCategory = knownCategory,
        )

        assertTrue("数组路径原样返回", turn is AzhangTurn.Entry)
        assertEquals("模型给了数组就不该惊动规则", 0, parseCalls)
    }

    @Test
    fun `模型不可用时不碰规则——那条路本来就有自己的降级`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = AzhangTurn.Unavailable("未找到模型文件"),
            parse = recorder(listOf(draft())),
            isKnownCategory = knownCategory,
        )

        assertTrue(turn is AzhangTurn.Unavailable)
        assertEquals(0, parseCalls)
    }

    @Test
    fun `规则解析不出条目 → 不出卡`() = runTest {
        val turn = withRulesEntryFallback(
            text = "你好",
            turn = AzhangTurn.Reply("你好，我是阿账"),
            parse = recorder(emptyList(), expect = "你好"),
            isKnownCategory = knownCategory,
        )

        turn as AzhangTurn.Reply
        assertTrue("没有条目就不挂卡", turn.entries.isEmpty())
        assertEquals("你好，我是阿账", turn.body)
    }

    @Test
    fun `金额不为正 → 不出卡`() = runTest {
        listOf("0", "0.00", "-35", "").forEach { amount ->
            parseCalls = 0
            val turn = withRulesEntryFallback(
                text = input,
                turn = AzhangTurn.Reply("回一句"),
                parse = recorder(listOf(draft(amount = amount))),
                isKnownCategory = knownCategory,
            )
            turn as AzhangTurn.Reply
            assertTrue("金额「$amount」不能出卡", turn.entries.isEmpty())
        }
    }

    @Test
    fun `分类落不进科目表 → 不出卡`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = AzhangTurn.Reply("回一句"),
            parse = recorder(listOf(draft(category = "自造分类"))),
            isKnownCategory = knownCategory,
        )

        turn as AzhangTurn.Reply
        assertTrue(turn.entries.isEmpty())
    }

    @Test
    fun `多条里只有合法的那几条挂上去`() = runTest {
        val turn = withRulesEntryFallback(
            text = input,
            turn = AzhangTurn.Reply("回一句"),
            parse = recorder(listOf(draft(amount = "35"), draft(amount = "0"), draft(category = "野分类"))),
            isKnownCategory = knownCategory,
        )

        turn as AzhangTurn.Reply
        assertEquals(1, turn.entries.size)
        assertEquals("35", turn.entries.first().amountText)
    }

    /** 模型回 `{"reply":"查账"}` → 上层转成的那一轮（body 已换成引导语）。 */
    private fun chatQueryReply() = AzhangTurn.Reply(
        body = AzhangChat.QUERY_FALLBACK_TEXT,
        isLedgerQuery = true,
    )
}
