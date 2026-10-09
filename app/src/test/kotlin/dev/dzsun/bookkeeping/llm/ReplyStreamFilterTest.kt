package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮面板「实时逐字显示」的正确性就压在这个类上：
 * 显示错一个字，用户看到的就是半截 JSON 或被吃掉的正文。
 */
class ReplyStreamFilterTest {

    /** 按 n 个字符切块喂，模拟真实的一 token 一块。 */
    private fun stream(text: String, chunkSize: Int = 1): String {
        val f = ReplyStreamFilter()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + chunkSize, text.length)
            sb.append(f.feed(text.substring(i, end)))
            i = end
        }
        sb.append(f.flush())
        return sb.toString()
    }

    @Test
    fun `回复被剥成纯文本`() {
        assertEquals("今天记得少喝奶茶", stream("""{"reply":"今天记得少喝奶茶"}"""))
    }

    @Test
    fun `逐字符喂也不会漏字或多字`() {
        assertEquals("这是一句比较长的回复，用来验证跨块切割的边界处理是否正确。",
            stream("""{"reply":"这是一句比较长的回复，用来验证跨块切割的边界处理是否正确。"}""", 1))
    }

    @Test
    fun `转义换行与引号被还原`() {
        assertEquals("第一行\n第二行\"引号\"和\\反斜杠",
            stream("""{"reply":"第一行\n第二行\"引号\"和\\反斜杠"}"""))
    }

    @Test
    fun `unicode 转义被还原`() {
        // 注意：Kotlin 连原始字符串都会在词法层处理 \uXXXX，
        // 所以这里必须用普通字符串 + 双反斜杠，才能真的把序列喂给过滤器。
        val raw = "{\"reply\":\"金额\\u0041\"}"
        assertEquals("金额A", stream(raw))
    }

    @Test
    fun `reply 后面的其它字段不会漏出来`() {
        assertEquals("好的", stream("""{"reply":"好的","confidence":0.9,"kind":"x"}"""))
    }

    @Test
    fun `数组是记账 不吐字也不吐 JSON 源码`() {
        val f = ReplyStreamFilter()
        val out = StringBuilder()
        """[{"kind":"expense","amount":28,"category":"交通"}]""".forEach {
            out.append(f.feed(it.toString()))
        }
        out.append(f.flush())
        assertEquals("", out.toString())
        assertEquals(ReplyStreamFilter.Kind.ENTRY, f.currentKind)
    }

    @Test
    fun `key 被切断在两个 chunk 之间也能认出来`() {
        val f = ReplyStreamFilter()
        val sb = StringBuilder()
        sb.append(f.feed("""{"rep"""))
        sb.append(f.feed("""ly":"你好"""))
        sb.append(f.feed(""""}"""))
        sb.append(f.flush())
        assertEquals("你好", sb.toString())
        assertEquals(ReplyStreamFilter.Kind.REPLY, f.currentKind)
    }

    @Test
    fun `不是 JSON 的输出整段丢弃 绝不把源码给用户看`() {
        val f = ReplyStreamFilter()
        val sb = StringBuilder()
        "Sorry, I cannot help with that.".forEach { sb.append(f.feed(it.toString())) }
        sb.append(f.flush())
        assertEquals("", sb.toString())
        assertEquals(ReplyStreamFilter.Kind.JUNK, f.currentKind)
    }

    @Test
    fun `空输入不炸`() {
        val f = ReplyStreamFilter()
        assertEquals("", f.feed(""))
        assertEquals("", f.flush())
        assertTrue(f.currentKind == ReplyStreamFilter.Kind.UNKNOWN)
    }
}
