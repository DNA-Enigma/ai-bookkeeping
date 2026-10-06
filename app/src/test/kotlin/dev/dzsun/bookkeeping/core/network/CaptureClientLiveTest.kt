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
 *
 * ⚠️ **读绿之前先看清它验到了什么。** 收据链路目前服务端不稳（实测 2026-10-06：
 * 4 次里 3 次失败，`normalize` 8 秒超时、`extract` agent 4 轮未出结果），
 * 所以图片那条多数会落到 `Failed` 分支，而失败任务的快照 `artifacts` 常常是 `{}`——
 * 那种情况下两条"服务端给了就得带出来"的断言都无从相比。
 * 也就是说：**绿 = 服务端这次给的东西我们没丢**，不等于「收据链路是通的」。
 * 置信度透出的真正证明在 `CaptureModelsTest`（拿实测快照原文做夹具，且已验证过
 * 去掉修复就会红）。
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

    private fun assumeLive() {
        assumeTrue("未设 DISPATCHER_LIVE=1，跳过联调", System.getenv("DISPATCHER_LIVE") == "1")
    }

    @Test
    fun `纯文本记账走完提交到终局`() = runBlocking {
        assumeLive()

        // 直路：不再为重试 P0-1c 留后门。那条路修好了，它要是再坏，这条就该红
        val outcome = liveCaptureClient().capture(
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
     * 客户端**不许比服务端少给**：快照里 `extract` 带了置信度，就必须原样透出来。
     *
     * 和下面那条失败断言同样的写法——不硬断言"一定有"，而是回查快照再比：
     * 服务端哪天不给置信度了不会假红（那是服务端的事，快照里看得见），
     * 但客户端把它丢了必定红。这正是我这边要守的那条线。
     */
    private suspend fun assertSurfacesServerConfidence(taskId: String, actual: Float?) {
        val snapshot = DispatcherClient(liveConfig()).getTask(taskId)
        val serverConfidence = snapshot.artifacts.receiptFields()?.confidence?.toFloat()
        if (serverConfidence != null) {
            assertEquals(
                "快照里 extract 带着置信度，客户端却丢了——自动入账会静默退化成每笔都问",
                serverConfidence,
                actual,
            )
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

        val outcome = liveCaptureClient().capture(
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
                // P0-a 的输入：收据走 vision_extract_then_write，extract 一定给置信度。
                // 它要是没了，自动入账会静默退化成"每笔都问"——不报错，只是白问一场
                assertNotNull("收据路径应透出整体置信度", outcome.confidence)
                assertTrue(
                    "置信度应落在 0..1：${outcome.confidence}",
                    outcome.confidence!! in 0f..1f,
                )
                // 而且必须与服务端快照里的值一致——"有值"不等于"是那个值"
                assertSurfacesServerConfidence(outcome.taskId, outcome.confidence)
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
                // 半截产物里的置信度同样不许丢：用户核对完照样要走「高置信直接入账」，
                // 丢了就每笔都得多问一次。**这条路才是实测里最常走到的**
                // （收据链路的下游节点目前不稳，多数运行落在这里）
                assertSurfacesServerConfidence(
                    outcome.taskId,
                    outcome.partialFields?.confidence?.toFloat(),
                )
            }
        }
    }
}
