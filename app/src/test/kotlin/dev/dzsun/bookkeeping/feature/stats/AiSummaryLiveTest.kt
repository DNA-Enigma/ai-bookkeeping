package dev.dzsun.bookkeeping.feature.stats

import dev.dzsun.bookkeeping.core.network.DispatcherClient
import dev.dzsun.bookkeeping.core.network.DispatcherConfig
import dev.dzsun.bookkeeping.core.platform.UuidGenerator
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **对着真实调度层**跑一次月度小结的联调用例。
 *
 * 默认跳过（不设 `DISPATCHER_LIVE=1` 时 `assume` 直接忽略），手动跑：
 *
 * ```
 * DISPATCHER_LIVE=1 tools/build.sh :app:testDebugUnitTest \
 *   --tests "*AiSummaryLiveTest*" --rerun-tasks
 * ```
 *
 * ⚠️ **读绿之前先看清它证明了什么。** 这条**不**证明「AI 一定能给出分析」——
 * 直答那一段在 dispatcher 里配了 8 秒超时（`config/routing.policy.yaml` 的
 * `direct_llm.timeout_ms`），实测 cheap 档位的上游经常不够，多数运行会落到
 * [AiSummaryOutcome.Unavailable]。它证明的是**客户端这一侧的形状是对的**：
 * 请求能发出去、能被路由到直答、终态能被读回、产物能按 `answer` 解出来，
 * 而且**认不出来时是干净地降级，不是崩、不是拿半截 JSON 糊过去**。
 *
 * 把「能跑通」变成可重复执行的断言，比在报告里写一句「已验过」可靠——
 * 契约或路由哪天漂了，这条会红。
 */
class AiSummaryLiveTest {

    private val baseUrl: String = System.getenv("DISPATCHER_BASE_URL") ?: "http://127.0.0.1:8010"

    private fun source(): DispatcherAiSummarySource {
        val config = DispatcherConfig().apply {
            baseUrl = this@AiSummaryLiveTest.baseUrl
            userId = "local"
        }
        return DispatcherAiSummarySource(DispatcherClient(config), config, UuidGenerator())
    }

    private fun facts() = MonthlySummaryFacts(
        periodLabel = "本月",
        from = LocalDate.of(2026, 10, 1),
        to = LocalDate.of(2026, 10, 31),
        currency = "CNY",
        expenseMinor = 320_000,
        incomeMinor = 500_000,
        dailyAverageMinor = 320_000 / 30,
        byCategory = listOf(
            CategorySlice("c1", "餐饮", 140_000, 0.4375f),
            CategorySlice("c2", "交通", 80_000, 0.25f),
        ),
        bars = listOf(
            MonthBar(YearMonth.of(2026, 9), 300_000, 500_000),
            MonthBar(YearMonth.of(2026, 10), 320_000, 500_000),
        ),
    )

    @Test
    fun `月度小结走完提交到终局且不抛异常`() = runBlocking {
        assumeTrue("未设 DISPATCHER_LIVE=1，跳过联调", System.getenv("DISPATCHER_LIVE") == "1")

        val outcome = source().summarize(facts())

        when (outcome) {
            is AiSummaryOutcome.Analyzed -> {
                assertTrue("分析正文不能是空的", outcome.text.isNotBlank())
                assertTrue("小结不能把请求原样回显", outcome.text != buildSummaryPrompt(facts()))
                outcome.confidence?.let {
                    assertTrue("置信度应落在 0..1：$it", it in 0f..1f)
                }
                println("[live] 拿到分析（confidence=${outcome.confidence}）：${outcome.text}")
            }

            // 上游抖动是常态，不是失败——降级路径本来就为它准备着。
            // 但**必须带上一个可读的原因**，否则界面无从解释为什么是本地小结。
            is AiSummaryOutcome.Unavailable -> {
                assertTrue("降级必须给出可读的原因", outcome.detail.isNotBlank())
                println("[live] 本次降级：${outcome.detail}")
            }
        }
    }
}
