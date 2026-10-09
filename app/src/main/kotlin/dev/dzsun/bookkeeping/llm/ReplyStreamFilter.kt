package dev.dzsun.bookkeeping.llm

/**
 * 把模型**流式吐出的 JSON** 剥成可以直接显示给用户的文字。
 *
 * 为什么要单独一层：悬浮面板要「实时逐字显示」，但模型的回复是
 * `{"reply":"答案"}` 这样的 JSON 包。如果把原文直接上屏，用户看到的是
 * `{"reply":"今天花` 这种半截代码 —— 比等 3 秒还难受。
 * 这个类一边吃 token 一边吐**纯正文**，同时判断这次到底是在记账（数组）还是在说话（对象）。
 *
 * 三条规则：
 *  1. **数组优先判定**：开头是 `[` → 这是记账，不再吐字，交上层出卡片；
 *  2. **对象只吐 `reply` 的值**：找到 `"reply":` 与起始引号才开始吐，遇到**未转义的收尾引号**就停，
 *     后面即使还有别的字段也不会漏出来；
 *  3. **转义要还原**：`\n` `\"` `\\` `\t` `\uXXXX` 按 JSON 规则还原，否则用户会看到字面量的 `\n`。
 *
 * 纯 Kotlin、无 Android 依赖 —— 边界（跨 chunk 切断、转义被切断、乱码输出）都能直接单测。
 */
class ReplyStreamFilter {

    /** 当前判定出来的回复形态。调用方据此决定收尾时怎么处理。 */
    enum class Kind { UNKNOWN, ENTRY, REPLY, JUNK }

    private val buf = StringBuilder()
    private var kind: Kind = Kind.UNKNOWN
    private var inReply = false      // 已经过了 `"reply":` 与起始引号，正在吐正文
    private var closed = false       // 已经吃到收尾引号
    private var escapePending = false

    val currentKind: Kind get() = kind

    /** 喂一段 token，返回这一段里**可以立即显示**的正文（可能为空串）。 */
    fun feed(chunk: String): String {
        if (chunk.isNotEmpty()) buf.append(chunk)
        return drain(final = false)
    }

    /** 流结束时调用，把缓冲里剩下的正文吐干净。 */
    fun flush(): String = drain(final = true)

    private fun drain(final: Boolean): String {
        val out = StringBuilder()

        if (kind == Kind.ENTRY || closed) { buf.setLength(0); return "" }

        if (kind == Kind.UNKNOWN) {
            // 还没看清是数组还是对象：跳过前导空白，看第一个有效字符
            var i = 0
            while (i < buf.length && buf[i].isWhitespace()) i++
            if (i > 0) buf.delete(0, i)
            if (buf.isEmpty()) return ""
            when (buf[0]) {
                '[' -> { kind = Kind.ENTRY; buf.setLength(0); return "" }
                '{' -> kind = Kind.REPLY
                else -> {
                    // 不是合法 JSON 开头。没读完就再等等（可能是被切断的前缀），
                    // 确定不是了就整段丢弃 —— 上层会降级，绝不把 JSON 源码给用户看。
                    if (final || buf.length > 8) { kind = Kind.JUNK; buf.setLength(0) }
                    return ""
                }
            }
        }

        // kind == REPLY：先找到 "reply": 起始引号
        if (!inReply) {
            val keyIdx = buf.indexOf(REPLY_KEY)
            if (keyIdx < 0) {
                // 可能 key 被切断了，留一截尾巴等下一个 chunk
                if (final) { buf.setLength(0) } else holdTail(REPLY_KEY.length - 1)
                return ""
            }
            // 找到 key 之后就**不能再裁 buffer**：裁了会把 key 的尾巴切掉，
            // 下一个 chunk 拼不出 `"reply"`，于是永远吐不出字（我第一版就是这么错的，
            // 五个单测一起红）。这里宁可多留几个字节，等下一块补齐。
            val colon = buf.indexOf(':', keyIdx + REPLY_KEY.length)
            if (colon < 0) { if (final) buf.setLength(0); return "" }
            var q = colon + 1
            while (q < buf.length && buf[q].isWhitespace()) q++
            if (q >= buf.length) { if (final) buf.setLength(0); return "" }
            if (buf[q] != '"') { kind = Kind.JUNK; buf.setLength(0); return "" }
            buf.delete(0, q + 1)
            inReply = true
        }

        // 吐正文，直到未转义的收尾引号
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (escapePending) {
                when (c) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> {
                        // \uXXXX —— 数字可能还没到齐
                        if (i + 4 >= buf.length) {
                            if (final) { i = buf.length; break }
                            // 留住这段转义，等下一块
                            buf.delete(0, i)
                            return out.toString()
                        }
                        val hex = buf.substring(i + 1, i + 5)
                        val code = hex.toIntOrNull(16)
                        if (code != null) out.append(code.toChar())
                        i += 4
                    }
                    else -> out.append(c)
                }
                escapePending = false
                i++
                continue
            }
            when (c) {
                '\\' -> { escapePending = true; i++ }
                '"' -> { closed = true; break }   // 收尾引号 → 正文结束
                else -> { out.append(c); i++ }
            }
        }
        // 吃掉已处理的部分；closed 时连收尾引号一起丢（下一次 feed 顶部会直接清空）
        if (closed) buf.setLength(0) else buf.delete(0, i)
        return out.toString()
    }

    private fun holdTail(n: Int) {
        if (n <= 0 || buf.length <= n) return
        buf.delete(0, buf.length - n)
    }

    companion object {
        private const val REPLY_KEY = "\"reply\""
    }
}
