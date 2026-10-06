package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

/**
 * 一次「拍/说 → 结构化字段」的完整往返。
 *
 * 把 [DispatcherClient] 的六个端点按契约要求的顺序串起来：上传媒体 → 提交任务 →
 * 订阅事件流到终态 → （需要时）取快照拿澄清。上层界面只看到 [CaptureOutcome]。
 *
 * 用事件流而不是轮询快照，是因为流能拿到逐节点的中间产物——这正是
 * [CaptureOutcome.Failed] 还能带上 `partialFields` 的原因。
 */
@Singleton
class CaptureClient @Inject constructor(
    private val client: DispatcherClient,
    private val config: DispatcherConfig,
    private val ids: IdGenerator,
) {

    suspend fun capture(request: CaptureRequest): CaptureOutcome = withTimeout(CAPTURE_TIMEOUT_MS) {
        val mediaRef = request.media?.let { payload ->
            val uploaded = client.uploadMedia(payload.bytes, payload.mime, payload.role)
            MediaRef(
                mediaId = uploaded.mediaId,
                kind = uploaded.kind,
                mime = uploaded.mime,
                bytes = uploaded.bytes,
                sha256 = uploaded.sha256,
                role = payload.role.wireName,
                capabilities = listOf(CAPABILITY_VISION_EXTRACT),
            )
        }

        val accepted = client.submitTask(
            TaskEnvelope(
                requestId = ids.newId(),
                // 有图片时用它的 sha256 做幂等键：同一张截图重复提交落到同一个任务，
                // 而不是入两次账。这也是契约示例里 `bookkeeping:u_123:sha256-…` 的用意。
                idempotencyKey = idempotencyKeyFor(mediaRef),
                identity = Identity(userId = config.userId),
                input = TaskInput(text = request.text, media = mediaRef?.let { listOf(it) }),
                declared = Declared(
                    intent = request.intent,
                    // ⚠️ 临时绕行，修好后应切回 true。理由与复现见
                    // docs/dispatcher-issues.md 的 P0-1c：把 authoritative 置 true 后
                    // 服务端会走另一套拆解，实测跑到 `record_expense` 模板时节点输入引用
                    // 解析不了（`envelope.user_text` 断了）而整单失败；false 时路由到
                    // single_tool_action / vision_extract_then_write，两条都跑得通。
                    authoritative = false,
                ),
                constraints = Constraints(dataSensitivity = Constraints.SENSITIVITY_FINANCIAL),
                client = ClientInfo(
                    app = CLIENT_APP,
                    appVersion = BuildConfig.VERSION_NAME,
                    supportsSse = true,
                    // 移动端必须为 true：退后台、切网、锁屏都会断连而任务仍在跑
                    supportsLastEventId = true,
                ),
            ),
        )

        // 契约：同步且已跑到终态时，POST 会把结果内联返回（200，带 result/error）。
        // 这时再开事件流是多余的——快照已经在手，流却要等重放，白多一个依赖。
        if (accepted.status in TaskSnapshot.TERMINAL_STATUSES) {
            return@withTimeout describe(accepted.taskId, client.getTask(accepted.taskId), null)
        }

        collectOutcome(accepted.taskId)
    }

    /**
     * 边收事件边攒中间产物，直到终态或需要澄清。
     *
     * `firstOrNull {}` 会在命中后取消上游，SSE 连接由 [DispatcherClient] 的
     * `invokeOnCompletion` 断开——所以这里不会漏连接。
     */
    private suspend fun collectOutcome(taskId: String): CaptureOutcome {
        var fields: ReceiptFields? = null

        val terminal = client.eventStream(taskId)
            .onEach { event ->
                event.parseReceiptFields()?.let { fields = it }
            }
            .firstOrNull { it.type in STOP_EVENT_TYPES }

        // 流没给出终态就结束了。退后台、切网、代理把 SSE 缓冲住，或服务端的流本身有问题，
        // 都会走到这里。契约允许以快照为准重建视图，所以退一步问快照——把一个服务端
        // 其实已经跑完的任务当成失败，等于让用户白拍一张。
        if (terminal == null) {
            return describe(taskId, awaitTerminalSnapshot(taskId), fields)
        }

        if (terminal.type == TaskEvent.TYPE_CLARIFICATION_NEEDED) {
            // 澄清问题的权威形状在快照里（事件载荷只是提示），所以取一次快照
            val snapshot = client.getTask(taskId)
            val clarification = snapshot.clarification
            return if (clarification == null) {
                CaptureOutcome.Failed(taskId, snapshot.error, fields)
            } else {
                CaptureOutcome.NeedsClarification(taskId, clarification, fields)
            }
        }

        return describe(taskId, runCatching { client.getTask(taskId) }.getOrNull(), fields)
    }

    /**
     * 快照 → 终局。**所有终态都从这里出**，不在事件类型与快照状态两处各判一次，
     * 否则两处口径一漂就会给出互相矛盾的结论。
     */
    private fun describe(
        taskId: String,
        snapshot: TaskSnapshot?,
        fields: ReceiptFields?,
    ): CaptureOutcome {
        if (snapshot == null) {
            return CaptureOutcome.Failed(
                taskId,
                Problem(
                    type = "about:blank",
                    title = "读不到任务状态",
                    status = 200,
                    code = "missing_artifacts",
                    retryable = true,
                    detail = "事件流已结束，快照也取不到——多半是网络断了",
                ),
                fields,
            )
        }

        if (snapshot.status == TaskSnapshot.STATUS_SUCCEEDED) {
            val entry = snapshot.artifacts.ledgerEntry()
            val resolved = fields
                ?: entry?.toReceiptFields()
                ?: snapshot.artifacts.receiptFields()
            return if (resolved == null) {
                CaptureOutcome.Failed(
                    taskId,
                    Problem(
                        type = "about:blank",
                        title = "任务成功但没有可用的抽取结果",
                        status = 200,
                        code = "missing_artifacts",
                        retryable = false,
                    ),
                    null,
                )
            } else {
                CaptureOutcome.Completed(taskId, resolved, entry)
            }
        }

        snapshot.clarification?.let {
            if (snapshot.isAwaitingClarification) {
                return CaptureOutcome.NeedsClarification(taskId, it, fields)
            }
        }

        return CaptureOutcome.Failed(taskId, snapshot.error, fields)
    }

    /**
     * 轮询快照直到终态。**这是退路，不是主路径**——只在事件流没给出终态就结束时用
     * （见 [collectOutcome] 的注释）。总时长由外层的 `withTimeout` 兜底，这里不再设上限。
     */
    private suspend fun awaitTerminalSnapshot(taskId: String): TaskSnapshot? {
        var consecutiveFailures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val snapshot = runCatching { client.getTask(taskId) }.getOrNull()
            if (snapshot == null) {
                if (++consecutiveFailures >= MAX_POLL_FAILURES) return null
                continue
            }
            consecutiveFailures = 0
            if (snapshot.isTerminal) return snapshot
        }
    }

    private fun idempotencyKeyFor(mediaRef: MediaRef?): String {
        val discriminator = mediaRef?.sha256?.let { "sha256-$it" } ?: "req-${ids.newId()}"
        return "bookkeeping:${config.userId}:$discriminator"
    }

    private fun TaskEvent.parseReceiptFields(): ReceiptFields? {
        if (type != TYPE_SUBTASK_COMPLETED) return null
        val output = payload.subtaskOutput(SUBTASK_EXTRACT) ?: return null
        return runCatching { CaptureJson.decodeFromJsonElement(ReceiptFields.serializer(), output) }
            .getOrNull()
    }

    private companion object {
        const val CLIENT_APP = "bookkeeping"
        const val CAPABILITY_VISION_EXTRACT = "vision.extract"
        const val SUBTASK_EXTRACT = "extract"

        /** 事件类型来自 `schemas/event.json` 的开放集合，未知类型一律忽略。 */
        const val TYPE_SUBTASK_COMPLETED = "subtask.completed"

        /**
         * 上限取 5 分钟：实测 `extract` 一个节点就跑了 31.7 秒（模板里却写着 20 秒超时），
         * 整条链的正常耗时不可低估。
         */
        const val CAPTURE_TIMEOUT_MS = 5 * 60 * 1000L

        /** 退路轮询的间隔。实测一个 `extract` 节点就要跑几十秒，2 秒一次不算密。 */
        const val POLL_INTERVAL_MS = 2_000L

        /** 连续这么多次取不到快照就放弃——网络真断了，再问也是白问。 */
        const val MAX_POLL_FAILURES = 5

        val STOP_EVENT_TYPES = setOf(
            TaskEvent.TYPE_CLARIFICATION_NEEDED,
            TaskEvent.TYPE_TASK_COMPLETED,
            TaskEvent.TYPE_TASK_FAILED,
            TaskEvent.TYPE_TASK_CANCELLED,
        )
    }
}
