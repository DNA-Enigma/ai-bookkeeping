package dev.dzsun.bookkeeping.llm

/**
 * 流式剔除 Spark-X2.5 的思考标记与控制 token。
 *
 * 纯 Kotlin、不依赖 Android → 可直接在 JVM 单元测试里验证。
 *
 * 模型事实（读自 GGUF 元数据 `Spark-X2.5-1.7B-Q4_K_M.gguf`）：
 *  - `general.architecture = spark2_5`
 *  - token[3] = `<think>`（进入思考）、token[4] = `</think>`（思考结束）
 *  - `tokenizer.chat_template` 在 add_generation_prompt 时按 `enable_thinking` 吐这两个之一
 *  - 另有 `<|...|>` 控制 token，以及模型输出里的 ``` 围栏
 *
 * 处理规则：
 *  - **思考段**：标记与其内容一律丢弃——用户要的是答案，不是推理过程
 *  - **`<|...|>` 控制 token**：整个丢弃
 *  - **``` 围栏**：只丢标记和语言标签（```json → 丢 `json`），**保留代码内容**
 *    （账单 JSON 全靠这块内容，删了就解析不了）
 *
 * 流式要点：chunk 可能正好切在标记中间（比如 `<th` + `ink>`），
 * 所以在没看到标记结尾之前，不能把可能属于标记的尾巴吐出去。
 */
class ThinkingStripper {

    private enum class Mode { TEXT, THINK }

    private var mode = Mode.TEXT
    /** 刚出思考段：把紧随其后的空白（含换行）一并吃掉，免得正文前面挂个空行 */
    private var skipLeadingWs = false
    /** 刚删掉一个 ``` 围栏，正在等它的语言标签（标签可能被切进下一个 chunk） */
    private var afterFence = false
    private val pending = StringBuilder()

    /** 喂一段模型输出，返回这段里可以安全展示的正文（可能为空串）。 */
    fun feed(chunk: String): String {
        if (chunk.isNotEmpty()) pending.append(chunk)
        return drain(final = false)
    }

    /**
     * 流结束时调用：把缓冲里剩下的正文吐干净。
     * 若此刻还卡在思考段里，说明模型没写完 `</think>`，剩余内容整段丢弃。
     */
    fun flush(): String = drain(final = true)

    private fun drain(final: Boolean): String {
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            if (mode == Mode.THINK) {
                val s = pending.toString()
                val end = s.indexOf(THINK_END)
                if (end >= 0) {
                    pending.delete(0, end + THINK_END.length)
                    mode = Mode.TEXT
                    skipLeadingWs = true
                    continue
                }
                if (final) {
                    pending.setLength(0)          // 没等到结束标记 → 整段思考丢弃
                } else {
                    holdBack(THINK_END.length - 1) // 只留可能属于结束标记的尾巴
                }
                break
            }

            // 上一个 chunk 刚开围栏、语言标签还没读全 → 先接着处理它。
            // 这里会改写 pending，所以快照必须在它之后再取（否则吐出去的是过期内容）。
            if (afterFence) {
                if (!dropFenceLangTag(final)) break
                afterFence = false
                if (pending.isEmpty()) break
            }

            val s = pending.toString()
            val iThink = s.indexOf(THINK_START)
            val iEnd = s.indexOf(THINK_END)
            val iFence = s.indexOf(FENCE)
            val iCtl = s.indexOf(CTL_START)
            val next = listOf(iThink, iEnd, iFence, iCtl).filter { it >= 0 }.minOrNull()

            if (next == null) {
                val safe = safeEmitLength(s, final)
                if (safe > 0) {
                    emit(out, s.substring(0, safe))
                    pending.delete(0, safe)
                }
                break
            }

            // 标记之前的正文先吐出去
            if (next > 0) {
                emit(out, s.substring(0, next))
                pending.delete(0, next)
            }

            when {
                next == iThink -> {
                    pending.delete(0, THINK_START.length)
                    mode = Mode.THINK
                }
                next == iEnd -> {
                    // 思考结束标记出现在正文态：chat_template 在非思考模式下会先吐它，
                    // 或者模型重复吐了一次 —— 丢掉，顺带吃掉后面紧贴的空白
                    pending.delete(0, THINK_END.length)
                    skipLeadingWs = true
                }
                next == iFence -> {
                    pending.delete(0, FENCE.length)
                    // 语言标签还没读全 → 先按住，别把 `json` 当正文吐出去
                    afterFence = true
                    if (!dropFenceLangTag(final)) break
                    afterFence = false
                }
                else -> {
                    val ps = pending.toString()
                    val gt = ps.indexOf('>')
                    if (gt >= 0) pending.delete(0, gt + 1)  // <|...|> 整个丢
                    else if (final) pending.setLength(0)
                    else break                                // 标记没读全，等下一块
                }
            }
        }
        return out.toString()
    }

    /**
     * ``` 后面紧跟的 `json` / `kotlin` 这类语言标签要删掉，否则正文开头就是一串脏字。
     * 只有「一串词字符 + 换行」才算语言标签；吃不准就不动，宁可留着也别误删正文。
     *
     * @return false = 数据还没读全（等下一个 chunk），调用方应当停手。
     */
    private fun dropFenceLangTag(final: Boolean): Boolean {
        val ps = pending.toString()
        if (ps.isEmpty()) return final
        val nl = ps.indexOf('\n')
        if (nl in 1..16 && isLangTag(ps.substring(0, nl))) {
            pending.delete(0, nl + 1)
            return true
        }
        // 还没等到换行：暂时看不出是不是语言标签，先别吐
        return !(ps.length <= 16 && isLangTag(ps))
    }

    private fun isLangTag(s: String) =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "_+-.#" }

    private fun emit(out: StringBuilder, text: String) {
        var start = 0
        if (skipLeadingWs) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start < text.length) skipLeadingWs = false
        }
        if (start < text.length) out.append(text, start, text.length)
    }

    /** 保留最后 n 个字符（它们可能是某个标记被切断的开头）。 */
    private fun holdBack(n: Int) {
        if (n <= 0 || pending.length <= n) return
        pending.delete(0, pending.length - n)
    }

    /**
     * 返回可以安全吐出的长度：尾部凡是「某个标记的前缀」的字符都要压住，
     * 等下一个 chunk 来了再判断它是标记还是正文。
     */
    private fun safeEmitLength(s: String, final: Boolean): Int {
        if (final) return s.length
        var hold = 0
        for (m in ALL_MARKERS) {
            val max = minOf(m.length - 1, s.length)
            var k = max
            while (k >= 1) {
                if (s.regionMatches(s.length - k, m, 0, k)) {
                    if (k > hold) hold = k
                    break
                }
                k--
            }
        }
        return (s.length - hold).coerceAtLeast(0)
    }

    companion object {
        // GGUF token[3] / token[4]（`Tokenizer.ggml.tokens`）—— 用字面量拼接，
        // 避免编辑器/传输链路把尖括号标记吞成空串（空串会让 indexOf 恒为 0 → 死循环）。
        const val THINK_START = "<" + "think>"
        const val THINK_END = "<" + "/" + "think>"
        const val FENCE = "```"
        const val CTL_START = "<|"

        private val ALL_MARKERS = listOf(THINK_START, THINK_END, FENCE, CTL_START)
    }
}
