package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 把一句自然语言问账交给调度层，换回一次**可本地执行的查询**。
 *
 * 这里是「判定」与「算术」的接缝：调度层判定（LLM 把「星巴克花了几次」翻成
 * 商户=星巴克、方向=支出、分组=无），[dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner]
 * 在本机算。客户端一个关键词表都没有。
 *
 * **用轮询而不是事件流。** 问账是一条 `single_tool_action` 的短任务
 * （实测 `max_llm_calls: 0`，整条链一秒出头），没有逐节点的中间产物可看——
 * 事件流在这里能给的只有进度动画，却要多担一条长连接的断线、重放、服务端流故障
 * （后两者实测都出过问题）。等它真需要展示进度时再换。
 */
@Singleton
class LedgerQueryClient @Inject constructor(
    private val client: DispatcherClient,
    private val config: DispatcherConfig,
    private val ids: IdGenerator,
) {

    suspend fun interpret(question: String): LedgerQueryOutcome {
        require(question.isNotBlank()) { "问账的问题不能为空" }

        return try {
            withTimeout(TIMEOUT_MS) {
                val accepted = client.submitTask(
                    TaskEnvelope(
                        requestId = ids.newId(),
                        identity = Identity(userId = config.userId),
                        input = TaskInput(text = question),
                        declared = Declared(intent = INTENT_LEDGER_QUERY, authoritative = true),
                        // 问账要读到用户的账目结构（分类名、商户名）。哪怕最后是本地算，
                        // 这层声明也要有：它让调度层能按金融数据的口径选路由。
                        constraints = Constraints(dataSensitivity = Constraints.SENSITIVITY_FINANCIAL),
                        client = ClientInfo(
                            app = CLIENT_APP,
                            appVersion = BuildConfig.VERSION_NAME,
                            supportsSse = true,
                            supportsLastEventId = true,
                        ),
                    ),
                )

                val snapshot = if (accepted.status in TaskSnapshot.TERMINAL_STATUSES) {
                    runCatching { client.getTask(accepted.taskId) }.getOrNull()
                } else {
                    awaitTerminal(accepted.taskId)
                }
                outcomeOf(accepted.taskId, snapshot)
            }
        } catch (e: DispatcherException) {
            // 还没拿到 task_id 就断了（提交这一步就失败）。
            // e.message 可能带着服务端 detail / 地址，界面只看白名单文案。
            LedgerQueryOutcome.Unavailable(null, e.problem, UserFacingErrors.ASK)
        }
    }

    private fun outcomeOf(taskId: String, snapshot: TaskSnapshot?): LedgerQueryOutcome {
        if (snapshot == null) {
            return LedgerQueryOutcome.Unavailable(
                taskId,
                null,
                UserFacingErrors.UNREACHABLE,
            )
        }

        if (snapshot.status != TaskSnapshot.STATUS_SUCCEEDED) {
            // 停在非成功终态：error.title / status 都是服务端内部词，不进界面。
            return LedgerQueryOutcome.Unavailable(
                taskId,
                snapshot.error,
                UserFacingErrors.ASK,
            )
        }

        // 成功但产不出查询——**这是当前服务端的常态，不是异常**。
        // 那条工具读的是服务端自己的参考账本（内存实现），返回的永远是空结果，
        // 而不是一句可执行的查询。这里如实说出来，绝不拿空结果冒充「你这个月花了 0 元」。
        //
        // 原因详情（工具名、账本归属）只活在本注释里，**不进界面**：
        // 用户改不了这些，界面只需要告诉他「换个问法」（见 UserFacingErrors.ASK）。
        val query = snapshot.artifacts.ledgerQuerySpec()?.toQuery()
            ?: return LedgerQueryOutcome.Unavailable(
                taskId,
                null,
                UserFacingErrors.ASK,
            )

        return LedgerQueryOutcome.Interpreted(taskId, query)
    }

    /**
     * 轮询到终态。
     *
     * 总时长由外层 `withTimeout` 兜底，这里不再设上限；连续取不到快照就放弃——
     * 网络真断了，再问也是白问。
     */
    private suspend fun awaitTerminal(taskId: String): TaskSnapshot? {
        var failures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val snapshot = runCatching { client.getTask(taskId) }.getOrNull()
            if (snapshot == null) {
                if (++failures >= MAX_POLL_FAILURES) return null
                continue
            }
            failures = 0
            if (snapshot.isTerminal) return snapshot
        }
    }

    private companion object {
        const val CLIENT_APP = "bookkeeping"

        /** 问账是短任务（实测一秒出头），一分钟足够覆盖评估 + 路由 + 工具调用。 */
        const val TIMEOUT_MS = 60_000L

        const val POLL_INTERVAL_MS = 500L

        const val MAX_POLL_FAILURES = 5
    }
}
