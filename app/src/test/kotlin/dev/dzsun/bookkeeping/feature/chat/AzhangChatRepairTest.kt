package dev.dzsun.bookkeeping.feature.chat

import dev.dzsun.bookkeeping.llm.SparkSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话输出的纠错链路：补救提示的构造、重试次数上限、纯文本兜底的数字护栏。
 *
 * 背景：1.7B 模型会吐「（暂无具体对话内容）」这类**不是 JSON 的原话**，
 * 没有纠错时用户看到的是「这次没答上」——聊天被拒的体验。
 * 这里钉的是**纠错之后仍然成立的边界**：数字绝不当回复、重试绝不超 1 次。
 */
class AzhangChatRepairTest {

    /** 永远解读不了的输出 —— 逼出重试与兜底分支。 */
    private val junk = "（暂无具体对话内容）"

    private fun parseNothing(@Suppress("UNUSED_PARAMETER") out: String): AzhangTurn? = null

    private fun recorder(sink: MutableList<Pair<LogLevel, String>>): (LogLevel, String) -> Unit =
        { level, msg -> sink += level to msg }

    // —— 补救提示的构造 ——

    @Test
    fun `补救提示就是原话加一句更硬的输出约束`() {
        assertEquals(
            SparkSession.MODE_CHAT + "hello" + "\n只输出 JSON，不要任何其他文字。",
            remedyPrompt("hello"),
        )
    }

    @Test
    fun `补救提示仍是对话模式不会串到记账解析`() {
        val prompt = remedyPrompt("午饭花了38")
        assertTrue("要带对话前缀", prompt.startsWith(SparkSession.MODE_CHAT))
        assertTrue("不能带记账解析前缀", !prompt.startsWith(SparkSession.MODE_PARSE))
        assertTrue("原话要原样带上", prompt.contains("午饭花了38"))
    }

    @Test
    fun `补救重试的次数上限是 1`() {
        assertEquals(1, MAX_REMEDY_ATTEMPTS)
    }

    // —— 重试次数与解析 ——

    @Test
    fun `首次就解析成功时不重试`() = runTest {
        var remedyCalls = 0
        val turn = repairChatOutput(
            text = "你好",
            firstOut = """{"reply":"你好，想记点什么？"}""",
            parse = { out -> extractForTest(out) },
            remedy = { remedyCalls++; null },
        ) { _, _ -> }

        assertTrue(turn is AzhangTurn.Reply)
        assertEquals("你好，想记点什么？", (turn as AzhangTurn.Reply).body)
        assertEquals("解析成功就不该惊动补救", 0, remedyCalls)
    }

    @Test
    fun `解析失败时只重试一次`() = runTest {
        var remedyCalls = 0
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val turn = repairChatOutput(
            text = "hello",
            firstOut = junk,
            parse = { out -> extractForTest(out) },
            remedy = { remedyCalls++; """{"reply":"你好呀"}""" },
            log = recorder(logs),
        )

        assertEquals("重试次数上限=1", 1, remedyCalls)
        assertTrue(turn is AzhangTurn.Reply)
        assertEquals("你好呀", (turn as AzhangTurn.Reply).body)
        assertTrue("要有一行 W 级重试日志", logs.any { it.first == LogLevel.W && it.second.contains("补救重试 1/1") })
        assertTrue(
            "要有一行 I 级纠错成功日志",
            logs.any { it.first == LogLevel.I && it.second.contains("补救重试 1 成功") },
        )
    }

    @Test
    fun `连重试也解读不了时也只试一次就兜底`() = runTest {
        var remedyCalls = 0
        val turn = repairChatOutput(
            text = "hello",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { remedyCalls++; junk },
            log = { _, _ -> },
        )
        assertEquals("重试次数上限=1", 1, remedyCalls)
        assertTrue(turn is AzhangTurn.Reply)
    }

    @Test
    fun `重试次数上限是可注入的参数`() = runTest {
        var remedyCalls = 0
        repairChatOutput(
            text = "hi",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { remedyCalls++; junk },
            maxRemedyAttempts = 3,
            log = { _, _ -> },
        )
        assertEquals(3, remedyCalls)
    }

    @Test
    fun `重试生成抛错不算致命仍按兜底处理`() = runTest {
        val turn = repairChatOutput(
            text = "hi",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { throw IllegalStateException("引擎炸了") },
            log = { _, _ -> },
        )
        assertTrue("纯文本兜底照旧", turn is AzhangTurn.Reply)
        assertEquals(junk, (turn as AzhangTurn.Reply).body)
    }

    // —— 纯文本兜底放行 ——

    @Test
    fun `问候语和纯中文句子可以当回复展示`() {
        listOf("你好", "你好呀，想记点什么？", "在吗？想记一笔就直接说。", "hello", "好的，你说")
            .forEach { assertEquals(it, displayablePlainText(it)) }
    }

    @Test
    fun `首尾空白会被吃掉再展示`() {
        assertEquals("你好", displayablePlainText("  你好 \n"))
    }

    // —— 数字护栏：这些绝不当 reply 展示 ——

    @Test
    fun `金额与百分比形态一律拒绝`() {
        listOf(
            "¥38", // ¥
            "这顿花了 ¥38",
            "28元", // 元
            "打车大概 28元",
            "二十八元", // 中文数字 + 元
            "一元也是钱",
            "占总支出 44%", // %
            "44%",
            "找零 3.50", // \d+\.\d{2}
            "3.50",
            "￥12",
        ).forEach { assertNull("「$it」不能当回复", displayablePlainText(it)) }
    }

    @Test
    fun `「元」字不构成金额时不拦`() {
        // 裸「元」会误伤日常句子：护栏拦的是编造的数字，不是这个字
        listOf("元旦快乐，新的一年账要记清", "今天是元旦", "欧元汇率不用你算")
            .forEach { assertEquals("「$it」应可展示", it, displayablePlainText(it)) }
    }

    @Test
    fun `JSON 残片和空数组不是一句话`() {
        listOf("""{"reply":"你好"}""", "{\"reply\":\"你好\"", "[]", "[{\"kind\":\"expense\"}]")
            .forEach { assertNull("「$it」不能当回复", displayablePlainText(it)) }
    }

    @Test
    fun `超长输出按碎碎念处理`() {
        assertNull(displayablePlainText("好".repeat(MAX_PLAIN_REPLY_CHARS + 1)))
        assertNotNull(displayablePlainText("好".repeat(MAX_PLAIN_REPLY_CHARS)))
    }

    // —— 护栏在纠错链路里真的生效 ——

    @Test
    fun `重试仍失败但吐出纯文本时按原话展示`() = runTest {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val turn = repairChatOutput(
            text = "hello",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { "你好，我是阿账，想记一笔就说一声。" },
            log = recorder(logs),
        )
        assertTrue(turn is AzhangTurn.Reply)
        assertEquals("你好，我是阿账，想记一笔就说一声。", (turn as AzhangTurn.Reply).body)
        assertTrue(!turn.isLedgerQuery)
        assertTrue(
            "要有一行纠错日志说明改按纯文本展示了",
            logs.any { it.first == LogLevel.I && it.second.contains("按纯文本回复展示") },
        )
    }

    @Test
    fun `重试输出带数字时不得展示仍返回降级`() = runTest {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val turn = repairChatOutput(
            text = "这个月花了多少",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { "本月支出 ¥3,200.00，占上月 44%" },
            log = recorder(logs),
        )
        assertTrue(turn is AzhangTurn.Unavailable)
        val reason = (turn as AzhangTurn.Unavailable).reason
        assertTrue("原因里要说清重试过：$reason", reason.contains("已补救重试 1 次"))
        assertTrue("要有一行降级日志", logs.any { it.first == LogLevel.I && it.second.contains("走降级") })
    }

    @Test
    fun `首次输出带数字且没有重试时也返回降级`() = runTest {
        val turn = repairChatOutput(
            text = "这个月花了多少",
            firstOut = "共 3.50 元",
            parse = ::parseNothing,
            remedy = { null },
            maxRemedyAttempts = 0,
            log = { _, _ -> },
        )
        assertTrue(turn is AzhangTurn.Unavailable)
        assertTrue(!(turn as AzhangTurn.Unavailable).reason.contains("已补救重试"))
    }

    @Test
    fun `重试没产出时用首次的原话兜底`() = runTest {
        val turn = repairChatOutput(
            text = "hi",
            firstOut = junk,
            parse = ::parseNothing,
            remedy = { null },
            log = { _, _ -> },
        )
        assertTrue(turn is AzhangTurn.Reply)
        assertEquals(junk, (turn as AzhangTurn.Reply).body)
    }

    @Test
    fun `降级原因保留原文片段便于定位`() = runTest {
        val turn = repairChatOutput(
            text = "hi",
            firstOut = "「」## 模型开始自言自语" + "x".repeat(200),
            parse = ::parseNothing,
            remedy = { "{\"reply\":\"依然是碎碎念\"" }, // 断掉的 JSON：不是一句话，也不该上屏
            log = { _, _ -> },
        )
        val reason = (turn as AzhangTurn.Unavailable).reason
        assertTrue("原文只露一段", reason.contains("依然是碎碎念"))
        assertTrue("不能把 200 字原文全糊上去", reason.length < 200)
    }

    /** 单测里的迷你解析器：认 `{"reply":...}` 就当成功，其余一律 null。 */
    private fun extractForTest(out: String): AzhangTurn? {
        val s = out.indexOf('{')
        val e = out.lastIndexOf('}')
        if (s !in 0 until e) return null
        val body = Regex("\"reply\"\\s*:\\s*\"([^\"]*)\"").find(out.substring(s, e + 1))
            ?.groupValues?.get(1) ?: return null
        return AzhangTurn.Reply(body)
    }
}
