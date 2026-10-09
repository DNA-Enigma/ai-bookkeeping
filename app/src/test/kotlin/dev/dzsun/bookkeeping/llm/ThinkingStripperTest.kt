package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThinkingStripper 的行为契约。
 *
 * 标记一律用字符串拼接构造——模型的真实标记就是这两个 ASCII 串，
 * 而直接写字面量容易在编辑/传输链路里被吞成空串（空串会让 indexOf 恒为 0）。
 */
class ThinkingStripperTest {

    private val thinkStart = "<" + "think>"
    private val thinkEnd = "<" + "/" + "think>"

    private fun run(chunks: List<String>): String {
        val st = ThinkingStripper()
        val sb = StringBuilder()
        chunks.forEach { sb.append(st.feed(it)) }
        sb.append(st.flush())
        return sb.toString()
    }

    @Test
    fun `标记常量就是 GGUF 里的原文`() {
        assertEquals("<think>", ThinkingStripper.THINK_START)
        assertEquals("</think>", ThinkingStripper.THINK_END)
        assertTrue(ThinkingStripper.THINK_START.isNotEmpty())
        assertTrue(ThinkingStripper.THINK_END.isNotEmpty())
    }

    @Test
    fun `纯文本原样透出`() {
        assertEquals("打车28元", run(listOf("打车28元")))
    }

    @Test
    fun `思考段连同内容整段剔除`() {
        val out = run(listOf("先想一下", thinkStart, "这里在推理，用户不该看到", thinkEnd, "最终答案"))
        assertEquals("先想一下最终答案", out)
    }

    @Test
    fun `标记跨 chunk 切断仍能识别`() {
        // 单字符切片，模拟真实的一 token 一块
        val full = "结账$thinkEnd" + "好嘞" + thinkStart + "内部推理" + thinkEnd + "共 28 元"
        val out = run(full.map { it.toString() })
        assertEquals("结账好嘞共 28 元", out)
    }

    @Test
    fun `思考结束后紧跟的空行被吃掉`() {
        val out = run(listOf(thinkStart + "推理", thinkEnd + "\n\n答案在此"))
        assertEquals("答案在此", out)
    }

    @Test
    fun `思考没走完就 flush 时剩余内容丢弃`() {
        assertEquals("", run(listOf(thinkStart + "推理到一半就没下文了")))
    }

    @Test
    fun `chunk 结尾是半个标记时不吐出去`() {
        val st = ThinkingStripper()
        assertEquals("abc", st.feed("abc<th"))   // abc 不可能是标记 → 先吐，只有 <th 被按住
        val out = st.feed("ink>real") + st.flush()
        // <think> 已识别 → 后面的 real 属于思考内容，应被丢弃
        assertEquals("", out)

        val st2 = ThinkingStripper()
        assertEquals("abc", st2.feed("abc</th"))      // 半个结束标记，正文先吐
        val out2 = st2.feed("ink>ok") + st2.flush()
        assertEquals("ok", out2)                      // 结束标记补齐 → 出思考
    }

    @Test
    fun `围栏只删标记与语言标签 保留代码内容`() {
        val out = run(listOf("```json\n{\"amount\":2800}\n```"))
        assertEquals("{\"amount\":2800}\n", out)
    }

    @Test
    fun `围栏标记被切断时语言标签不会漏出来`() {
        val out = run(listOf("```js", "on\nalert(1)\n", "```"))
        assertEquals("alert(1)\n", out)
    }

    @Test
    fun `控制 token 整个丢弃`() {
        val out = run(listOf("<|" + "eot|>正文" + "<|" + "foo|>尾巴"))
        assertEquals("正文尾巴", out)
    }

    @Test
    fun `没有结束标记的控制 token 在 flush 时不残留`() {
        val out = run(listOf("前文<|" + "unclosed"))
        assertEquals("前文", out)
    }
}
