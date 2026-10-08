package dev.dzsun.bookkeeping.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话页的一句话该走哪条链路。
 *
 * 真机实测：打个招呼（「你好」「在吗」）会被扔进问账链路，而问账只会答账目查询，
 * 于是必然回一句「这句话暂时没听懂，换个问法试试」——用户第一次开口就被劝退。
 */
class ChatRoutingTest {

    // —— 寒暄必须是寒暄，不是「没听懂」 ——

    @Test
    fun `常见招呼都走寒暄`() {
        listOf("你好", "您好", "在吗", "在么", "在不在", "嗨", "哈喽", "早上好", "晚上好")
            .forEach { assertTrue("「$it」应走寒暄", classifyChatInput(it) is ChatIntent.SmallTalk) }
    }

    @Test
    fun `英文招呼走寒暄`() {
        listOf("hi", "Hi", "hello", "Hello", "hey").forEach {
            assertTrue("「$it」应走寒暄", classifyChatInput(it) is ChatIntent.SmallTalk)
        }
    }

    @Test
    fun `应答与道别也走寒暄`() {
        listOf("谢谢", "好的", "收到", "晚安", "再见", "thanks").forEach {
            assertTrue("「$it」应走寒暄", classifyChatInput(it) is ChatIntent.SmallTalk)
        }
    }

    @Test
    fun `寒暄的接话要主动说明能干什么`() {
        val reply = (classifyChatInput("你好") as ChatIntent.SmallTalk).reply
        assertTrue("应提到记账", reply.contains("记"))
        assertTrue("应给出一个能直接照说的例子", reply.contains("打车 28") || reply.contains("这个月餐饮花了多少"))
    }

    @Test
    fun `英文的 hi 不会在别的词里被误判`() {
        // 「this」「history」里都含 hi，但那是在问账，不是打招呼
        assertTrue(classifyChatInput("this") is ChatIntent.Ask)
        assertTrue(classifyChatInput("history") is ChatIntent.Ask)
    }

    // —— 含金额永远优先，不能被寒暄顶掉 ——

    @Test
    fun `你好我花了三十五要走记账`() {
        val intent = classifyChatInput("你好我花了 35")
        assertEquals(ChatIntent.Record("35"), intent)
    }

    @Test
    fun `带招呼语的记账照样识别金额`() {
        assertEquals(ChatIntent.Record("28"), classifyChatInput("hi 打车 28"))
        assertEquals(ChatIntent.Record("12.50"), classifyChatInput("谢谢，午饭 12.50"))
    }

    @Test
    fun `金额取用户原样的数字串_不转浮点`() {
        // 12.50 若被格式化成 Double 再回来，就会变成 12.4999…
        assertEquals("12.50", (classifyChatInput("午饭 12.50") as ChatIntent.Record).amountText)
    }

    // —— 其余两条链路不受影响 ——

    @Test
    fun `省钱建议仍走建议`() {
        assertTrue(classifyChatInput("给我省钱建议") is ChatIntent.Advice)
        assertTrue(classifyChatInput("怎么存钱") is ChatIntent.Advice)
    }

    @Test
    fun `账目问题走问账`() {
        val intent = classifyChatInput("这个月餐饮花了多少")
        assertEquals(ChatIntent.Ask("这个月餐饮花了多少"), intent)
    }

    // —— 答不出来时给一个能成的例子，而不是「换个问法」 ——

    @Test
    fun `问账答不出来时给出可成功的示例`() {
        val reply = askUnavailableReply("这句话暂时没听懂，换个问法试试")
        assertTrue("应给一个能直接照问的例子", reply.contains("这个月餐饮花了多少"))
        assertTrue("记账也给个例子", reply.contains("打车 28"))
    }
}
