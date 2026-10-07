package dev.dzsun.bookkeeping.feature.stats

import dev.dzsun.bookkeeping.core.network.Problem
import dev.dzsun.bookkeeping.core.network.TaskSnapshot
import dev.dzsun.bookkeeping.core.network.UserFacingErrors
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 月度小结里**不碰网络的那几段**：prompt 的组装、直答产物的解析、终局快照到结局的映射。
 *
 * 这里刻意不测「能不能真的问出话来」——那取决于调度层与模型，不是客户端能保证的，
 * 硬断言只会变成一条随上游抖动的假红。真正会悄悄坏掉、坏了又没人发现的是另外三类：
 *
 * 1. **prompt 里的数字**：金额格式化错一位、占比算反、漏掉趋势，模型照样输出一段
 *    读起来很对的话，而用户会当真。
 * 2. **解析的判别**：把一段没有 `answer` 的产物当成小结，等于拿半截 JSON 糊用户。
 * 3. **终局映射**：任务失败/快照取不到时必须走降级，而不是拿一句编的话顶上。
 */
class AiSummaryTest {

    private fun facts(
        expenseMinor: Long = 320_000,
        incomeMinor: Long = 500_000,
        categories: List<CategorySlice> = listOf(
            CategorySlice("c1", "餐饮", 140_000, 0.4375f),
            CategorySlice("c2", "交通", 80_000, 0.25f),
        ),
        bars: List<MonthBar> = listOf(
            MonthBar(YearMonth.of(2026, 8), 280_000, 500_000),
            MonthBar(YearMonth.of(2026, 9), 300_000, 500_000),
        ),
    ) = MonthlySummaryFacts(
        periodLabel = "本月",
        from = LocalDate.of(2026, 10, 1),
        to = LocalDate.of(2026, 10, 31),
        currency = "CNY",
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        dailyAverageMinor = expenseMinor / 30,
        byCategory = categories,
        bars = bars,
    )

    // ------------------------------------------------------------ prompt

    @Test
    fun `prompt 带上总额、分类占比、日均与趋势`() {
        val prompt = buildSummaryPrompt(facts())

        // 金额走 Money 的格式化，千分位与小数位都在——不是拼出来的浮点串
        assertTrue("缺总支出：\n$prompt", prompt.contains("3,200.00"))
        assertTrue("缺总收入：\n$prompt", prompt.contains("5,000.00"))
        assertTrue("缺分类金额：\n$prompt", prompt.contains("1,400.00"))
        assertTrue("占比没有转成百分数：\n$prompt", prompt.contains("占 44%"))
        assertTrue("缺区间：\n$prompt", prompt.contains("2026-10-01 至 2026-10-31"))
        assertTrue("缺趋势：\n$prompt", prompt.contains("2026-08"))
        assertTrue("缺日均：\n$prompt", prompt.contains("日均支出"))
    }

    @Test
    fun `prompt 要求只用给出的数字`() {
        // 模型手边同时有总额和分类，最省事的编造就是把两项相加再报一个不存在的合计；
        // 这一句是拦它的，删掉就等于默许
        val prompt = buildSummaryPrompt(facts())
        assertTrue(prompt.contains("只使用上面给出的数字"))
    }

    @Test
    fun `没有分类和趋势时不输出空的小标题`() {
        val prompt = buildSummaryPrompt(facts(categories = emptyList(), bars = emptyList()))
        assertFalse("空分类不该留一个没有内容的标题", prompt.contains("支出分类"))
        assertFalse("空趋势不该留一个没有内容的标题", prompt.contains("近 6 个月支出趋势"))
    }

    @Test
    fun `只有收入没有支出也算有数据`() {
        // 只发工资、这个月还没花钱：不去问模型就等于永远看不到小结
        val onlyIncome = facts(expenseMinor = 0, incomeMinor = 500_000, categories = emptyList())
        assertFalse(onlyIncome.isEmpty)
    }

    @Test
    fun `一个字都没记时判为空`() {
        assertTrue(facts(expenseMinor = 0, incomeMinor = 0, categories = emptyList()).isEmpty)
    }

    // ------------------------------------------------------------ 产物解析

    @Test
    fun `从直答产物里读出 answer 与 confidence`() {
        val artifacts = json(
            """{"answer":"本月餐饮占比偏高。","tier":"cheap","latency_ms":2755,"confidence":0.85}""",
        )
        val answer = parseSummaryAnswer(artifacts)

        assertEquals("本月餐饮占比偏高。", answer?.text)
        assertEquals(0.85f, answer?.confidence)
    }

    @Test
    fun `产物多包一层也能读出来`() {
        // 同一个 task 换个入口就多一层：/result 返回的是 {"task_id":…, "artifacts":{…}}
        val wrapped = json("""{"task_id":"t1","artifacts":{"answer":"一段话"}}""")
        assertEquals("一段话", parseSummaryAnswer(wrapped)?.text)
    }

    @Test
    fun `没有 answer 就不是小结`() {
        // 单步工具那条路的产物长得就是这个样子（query_ledger 的空账本）。
        // 认错了就会把一段 JSON 当分析摆在用户面前。
        val notAnAnswer = json("""{"main":{"entries":[],"count":0}}""")
        assertNull(parseSummaryAnswer(notAnAnswer))
    }

    @Test
    fun `空白的 answer 不算数`() {
        assertNull(parseSummaryAnswer(json("""{"answer":"   "}""")))
        assertNull(parseSummaryAnswer(json("""{"answer":null}""")))
        assertNull(parseSummaryAnswer(null))
    }

    @Test
    fun `没有 confidence 时是 null 而不是零`() {
        // 0.0 会被下游当成「模型很没把握」，null 才是「这次没给」——两回事
        val answer = parseSummaryAnswer(json("""{"answer":"一段话"}"""))
        assertEquals("一段话", answer?.text)
        assertNull(answer?.confidence)
    }

    // ------------------------------------------------------------ 终局映射

    @Test
    fun `成功且产物可读时给出分析`() {
        val outcome = summaryOutcomeOf(
            "task_1",
            snapshot("succeeded", """{"answer":"这个月餐饮花得最多。","confidence":0.9}"""),
        )

        assertTrue(outcome is AiSummaryOutcome.Analyzed)
        val analyzed = outcome as AiSummaryOutcome.Analyzed
        assertEquals("这个月餐饮花得最多。", analyzed.text)
        assertEquals("task_1", analyzed.taskId)
    }

    @Test
    fun `任务失败时走降级且不透传服务端原因`() {
        // 服务端 detail（这里故意写成带主机名风格的原文）**不进界面**：
        // 用户改不了它，排查信息只活在异常对象里。见 UserFacingErrors。
        val outcome = summaryOutcomeOf("task_2", snapshot("failed", null, detail = "upstream.timeout at gateway.internal"))

        assertTrue(outcome is AiSummaryOutcome.Unavailable)
        assertEquals(UserFacingErrors.GENERIC, (outcome as AiSummaryOutcome.Unavailable).detail)
    }

    @Test
    fun `成功但没有可读产物也要降级`() {
        val outcome = summaryOutcomeOf("task_3", snapshot("succeeded", """{"main":{}}"""))
        assertTrue(outcome is AiSummaryOutcome.Unavailable)
    }

    @Test
    fun `快照取不到时降级而不是崩`() {
        val outcome = summaryOutcomeOf("task_4", null)
        assertTrue(outcome is AiSummaryOutcome.Unavailable)
    }

    // ------------------------------------------------------------ 夹具

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun snapshot(
        status: String,
        artifacts: String?,
        detail: String? = null,
    ) = TaskSnapshot(
        taskId = "task_x",
        userId = "local",
        status = status,
        source = "app",
        mode = "sync",
        progress = if (status == "succeeded") 1.0 else 0.0,
        createdAt = "2026-10-07T00:00:00Z",
        updatedAt = "2026-10-07T00:00:01Z",
        artifacts = artifacts?.let { json(it) },
        error = detail?.let {
            Problem(
                type = "about:blank",
                title = "失败",
                status = 502,
                code = "upstream_llm_error",
                retryable = true,
                detail = it,
            )
        },
    )
}
