package dev.dzsun.bookkeeping.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回复字段的同义容错（软测 TC-22 / 发现 3）：
 * 补救重试吐 `{"content": "…"}` 缺 `reply` 时不能判失败；
 * 但护栏不放松——数字形态、JSON 残片、超长、空串照旧拦下，数组仍走记账解析。
 */
class ExtractReplyTest {

    // ---------- 同义字段放行 ----------

    @Test
    fun `content 字段放行`() {
        assertEquals(
            "28是一个表示数量的数字，在日常生活、数据记录、统计等方面都有使用场景",
            displayableChatReply("""{"content": "28是一个表示数量的数字，在日常生活、数据记录、统计等方面都有使用场景"}"""),
        )
    }

    @Test
    fun `text 字段放行`() {
        assertEquals("好的，想记一笔什么账？", displayableChatReply("""{"text": "好的，想记一笔什么账？"}"""))
    }

    @Test
    fun `message 字段放行`() {
        assertEquals("收到", displayableChatReply("""{"message": "收到"}"""))
    }

    @Test
    fun `data 嵌套一层的 reply 放行`() {
        assertEquals("你好，我是阿账", displayableChatReply("""{"data": {"reply": "你好，我是阿账"}}"""))
    }

    @Test
    fun `result 嵌套一层的 content 放行`() {
        assertEquals("在的", displayableChatReply("""{"result": {"content": "在的"}}"""))
    }

    @Test
    fun `reply 优先于同级同义字段`() {
        assertEquals("正主", displayableChatReply("""{"reply": "正主", "content": "陪跑"}"""))
    }

    @Test
    fun `顶层没有才进嵌套——嵌套里的 reply 先于顶层 content 之后检查`() {
        // 顶层有 content 就用顶层的，不再进嵌套
        assertEquals("顶层", displayableChatReply("""{"content": "顶层", "data": {"reply": "嵌套"}}"""))
    }

    // ---------- 不放行 ----------

    @Test
    fun `reply 空串不放行`() {
        assertNull(displayableChatReply("""{"reply": ""}"""))
    }

    @Test
    fun `reply 空白串不放行`() {
        assertNull(displayableChatReply("""{"reply": "   "}"""))
    }

    @Test
    fun `reply null 不放行也不当字符串`() {
        assertNull(displayableChatReply("""{"reply": null}"""))
    }

    @Test
    fun `同义字段全是空串不放行`() {
        assertNull(displayableChatReply("""{"reply": "", "content": "  ", "text": null}"""))
    }

    @Test
    fun `嵌套不递归——二层包装取不到`() {
        assertNull(displayableChatReply("""{"data": {"result": {"reply": "埋太深"}}}"""))
    }

    // ---------- 护栏不放松 ----------

    @Test
    fun `金额形态照旧拦下`() {
        assertNull(displayableChatReply("""{"reply": "这笔一共 ¥35.00"}"""))
        assertNull(displayableChatReply("""{"content": "打车 28元 要记吗"}"""))
    }

    @Test
    fun `JSON 残片照旧拦下`() {
        // 正文里带大括号（合法 JSON、转义过的字符串），护栏要拦的是内容不是外层形状
        assertNull(displayableChatReply("""{"content": "输出应该是 {\"reply\": \"x\"} 这样"}"""))
    }

    @Test
    fun `超长照旧拦下`() {
        val long = "话".repeat(161)
        assertNull(displayableChatReply("""{"reply": "$long"}"""))
    }

    @Test
    fun `非 JSON 纯文本不放行——仍走补救重试`() {
        assertNull(displayableChatReply("28是一个表示数量的数字，具体含义看场景"))
    }

    // ---------- 数组不受影响 ----------

    @Test
    fun `记账数组不会被当成回复正文`() {
        // 数组由 interpret 的 decodeEntries 先接走；extractReply 摘出的对象里没有回复字段 → null
        assertNull(
            displayableChatReply(
                """[{"kind":"expense","amount":35,"category":"餐饮","note":"午饭","payee":"","confidence":0.9}]""",
            ),
        )
    }

    @Test
    fun `带 reply 的对象数组也能摘出正文`() {
        // 实测出现过 [{"reply":"你好…"}] 这种形状（软测 TC-08），要能当回复接住
        assertEquals("你好，我是记账管家", displayableChatReply("""[{"reply":"你好，我是记账管家"}]"""))
    }

    @Test
    fun `完全看不懂的输出返回 null 而不是抛异常`() {
        assertNull(displayableChatReply(""))
        assertNull(displayableChatReply("只是 plain text"))
        assertNull(displayableChatReply("{半个对象"))
    }
}
