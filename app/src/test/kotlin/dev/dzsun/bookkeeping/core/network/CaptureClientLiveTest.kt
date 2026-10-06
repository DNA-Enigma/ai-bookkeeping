package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.core.platform.UuidGenerator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **对着真实调度层**跑 M2 闭环的联调用例。
 *
 * 默认跳过（不设 `DISPATCHER_LIVE=1` 时 `assume` 直接忽略），所以不会拖慢日常构建，
 * 也不会在没起服务时把构建弄红。手动跑：
 *
 * ```
 * DISPATCHER_LIVE=1 DISPATCHER_LIVE_RECEIPT=/tmp/receipt.png \
 *   tools/build.sh :app:testDebugUnitTest --tests "*CaptureClientLiveTest*" --rerun-tasks
 * ```
 *
 * 之所以值得留这么一份：`core/network` 的形状全是**实测反推**的，而实测结论
 * 一旦写成文档就会过期。把「能跑通」变成一条可重复执行的断言，比在报告里
 * 写一句「已验过」可靠——下次契约漂了，这条会红。
 */
class CaptureClientLiveTest {

    private val baseUrl: String = System.getenv("DISPATCHER_BASE_URL") ?: "http://127.0.0.1:8010"

    private fun liveConfig() = DispatcherConfig().apply {
        baseUrl = this@CaptureClientLiveTest.baseUrl
        userId = "local"
    }

    private fun liveCaptureClient(): CaptureClient {
        val config = liveConfig()
        return CaptureClient(DispatcherClient(config), config, UuidGenerator())
    }

    private companion object {
        const val EMPTY_PLAN_RETRIES = 3
    }

    private fun assumeLive() {
        assumeTrue("未设 DISPATCHER_LIVE=1，跳过联调", System.getenv("DISPATCHER_LIVE") == "1")
    }

    /**
     * 服务端的拆解器有个**间歇性**的 P0：偶尔会「strategy=single_step 但节点数为 0」，
     * 直接 422（见 `docs/dispatcher-issues.md` 的 P0-1c）。它发生在任务创建**之前**，
     * 不是客户端闭环的问题，所以这里重试几次——但重试都失败就照样红，
     * 免得把一个持续存在的故障伪装成"偶发"。
     */
    private suspend fun captureRetryingOnEmptyPlan(request: CaptureRequest): CaptureOutcome {
        var last: DispatcherException? = null
        repeat(EMPTY_PLAN_RETRIES) {
            try {
                return liveCaptureClient().capture(request)
            } catch (e: DispatcherException) {
                if (e.code != Problem.CODE_POLICY_VIOLATION) throw e
                last = e
            }
        }
        throw AssertionError("连续 $EMPTY_PLAN_RETRIES 次都被拆解器拒绝（P0-1c 仍在）：${last?.message}")
    }

    @Test
    fun `纯文本记账走完提交到终局`() = runBlocking {
        assumeLive()

        val outcome = captureRetryingOnEmptyPlan(
            CaptureRequest(intent = "bookkeeping.capture_from_text", text = "午饭花了38"),
        )

        // 无论走哪条路由、有没有澄清，都不该是"整单失败且没有产物"
        when (outcome) {
            is CaptureOutcome.Completed -> {
                val money = outcome.fields.money()
                assertNotNull("成功的任务必须给出金额", money)
                assertEquals("金额必须落在最小单位整数上", 3800L, money!!.amountMinor)
                assertEquals("CNY", money.currency)
            }

            is CaptureOutcome.NeedsClarification -> {
                assertTrue("澄清必须带上问题原文", outcome.clarification.question.isNotBlank())
            }

            is CaptureOutcome.Failed -> {
                assertNotNull("失败必须给出可读的原因", outcome.problem)
                assertCarriesEveryArtifactTheServerHas(outcome)
            }
        }
    }

    /**
     * 失败时客户端**不许比服务端少给**：快照里若已经有完成节点的产物，就必须带出来
     * （契约明写「已完成节点的产物保留并如实上报」）。
     *
     * 这里刻意不写成 `assertNotNull(outcome.partialFields)`——实测文本任务失败时
     * `artifacts` 是 `{}`（拆解阶段就崩了，一个节点都没产出过），那种情况**本来就没有
     * 产物可带**，硬断言只会变成一条假红。改成回查一次快照再比，就两种情形都成立：
     * 服务端没产物时不假红，客户端吞产物时必定红。
     */
    private suspend fun assertCarriesEveryArtifactTheServerHas(outcome: CaptureOutcome.Failed) {
        val snapshot = DispatcherClient(liveConfig()).getTask(outcome.taskId)
        val serverHasFields = snapshot.artifacts.ledgerEntry() != null ||
            snapshot.artifacts.receiptFields() != null
        if (serverHasFields) {
            assertNotNull(
                "快照里有已完成节点的产物，客户端却丢了——用户等于白拍一张",
                outcome.partialFields,
            )
        }
    }

    @Test
    fun `收据截图走完上传到终局`() = runBlocking {
        assumeLive()
        val path = System.getenv("DISPATCHER_LIVE_RECEIPT")
        assumeTrue("未设 DISPATCHER_LIVE_RECEIPT，跳过图片联调", path != null)
        val image = File(path!!)
        assumeTrue("收据图片不存在：$path", image.isFile)

        val outcome = captureRetryingOnEmptyPlan(
            CaptureRequest(
                intent = "bookkeeping.capture_from_receipt",
                media = MediaPayload(image.readBytes(), "image/png", MediaRole.SCREENSHOT),
            ),
        )

        when (outcome) {
            is CaptureOutcome.Completed -> {
                assertNotNull("收据任务必须抽出金额", outcome.fields.money())
                assertNotNull("收据任务必须抽出商户名", outcome.fields.merchant)
                // 服务端归类的产物：entry 必须被认出来，否则分类名和幂等 id 都丢了
                assertNotNull("必须能从 artifacts 里认出 LedgerEntry", outcome.entry)
                assertNotNull("LedgerEntry 必须带稳定的 entry_id", outcome.entry?.entryId)
            }

            is CaptureOutcome.NeedsClarification -> {
                // 置信度低于策略阈值时任务会停下来问用户，这是正常路径
                assertTrue(outcome.clarification.question.isNotBlank())
            }

            is CaptureOutcome.Failed -> {
                // 实测里 extract 抽对了字段却因下游节点超时整单失败——
                // 这时半截产物必须还在，用户核对一下就能入账。
                assertNotNull("失败必须给出可读的原因", outcome.problem)
                assertCarriesEveryArtifactTheServerHas(outcome)
            }
        }
    }
}
