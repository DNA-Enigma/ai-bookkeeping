package dev.dzsun.bookkeeping.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 调度层契约的数据结构。
 *
 * 来源是 `/home/dzsun/projects/smart-dispatcher/` 里已冻结的契约
 * 目录 `schemas` 下的 `*.json` 与 `openapi.yaml`，字段名与线上一一对应，不做重命名。
 *
 * **所有解析都开 `ignoreUnknownKeys`**：契约承诺只做加法（新增可选字段），
 * 而手机应用没法强制升级，遇到不认识的字段必须容忍而不是崩。
 * 同理，[TaskEvent.data] 是 [JsonObject] 而不是封闭类型——未知事件类型要能安全落地。
 */

// ---------------------------------------------------------------- 请求

@Serializable
data class TaskEnvelope(
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("parent_task_id") val parentTaskId: String? = null,
    val identity: Identity,
    val input: TaskInput,
    val declared: Declared? = null,
    val constraints: Constraints? = null,
    val client: ClientInfo? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class Identity(
    @SerialName("tenant_id") val tenantId: String = DEFAULT_TENANT,
    @SerialName("user_id") val userId: String,
    /** BCP 47，如 zh-CN。 */
    val locale: String? = null,
    /** IANA 时区，如 Asia/Shanghai。影响日报、对账等时间窗口语义。 */
    val timezone: String? = null,
) {
    companion object {
        const val DEFAULT_TENANT = "default"
    }
}

@Serializable
data class TaskInput(
    val text: String? = null,
    /** 只放引用不放 base64——v1 的媒体先走 [DispatcherClient.uploadMedia]。 */
    val media: List<MediaRef>? = null,
)

@Serializable
data class MediaRef(
    @SerialName("media_id") val mediaId: String,
    val kind: String,
    val mime: String,
    val bytes: Long? = null,
    /** 服务端计算并返回，用于跨设备去重与幂等判定。 */
    val sha256: String? = null,
    val role: String? = null,
    val capabilities: List<String>? = null,
)

/**
 * 调用方对本次请求的显式声明。
 *
 * 契约管这叫「唯一的合法快捷路径」：结构化信号，**不得用关键词匹配来替代**。
 * 界面知道用户点的是「记一笔」还是「查账」时，就该写在这里，省掉一次评估。
 */
@Serializable
data class Declared(
    /** 取值应来自 `config/taxonomy.yaml`，如 `bookkeeping.capture_from_receipt`。 */
    val intent: String? = null,
    val capability: String? = null,
    /** true 表示调用方断言自己已知类型；是否真的跳过评估由策略决定。 */
    val authoritative: Boolean = false,
)

@Serializable
data class Constraints(
    @SerialName("mode_preference") val modePreference: String = MODE_AUTO,
    /** 是否真的中断由策略的 budget.enforcement.mode 决定，默认 advisory 只告警。 */
    @SerialName("max_cost") val maxCost: Double? = null,
    @SerialName("max_wall_ms") val maxWallMs: Long? = null,
    /** 档位名是配置性词表，故此处不设枚举，由守卫校验成员资格。 */
    @SerialName("allowed_model_tiers") val allowedModelTiers: List<String>? = null,
    /** 金融数据不得选择会把原图外发的路由。 */
    @SerialName("data_sensitivity") val dataSensitivity: String? = null,
) {
    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_SYNC = "sync"
        const val MODE_ASYNC = "async"

        const val SENSITIVITY_FINANCIAL = "financial"
    }
}

@Serializable
data class ClientInfo(
    val app: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("min_supported_client_version") val minSupportedClientVersion: String? = null,
    @SerialName("supports_sse") val supportsSse: Boolean = true,
    /** 移动端必须置 true：退后台、切网、锁屏都会断连。 */
    @SerialName("supports_last_event_id") val supportsLastEventId: Boolean = false,
)

// ---------------------------------------------------------------- 响应

@Serializable
data class MediaUpload(
    @SerialName("media_id") val mediaId: String,
    val kind: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** 短期签名 URL，**仅供本地预览，不要持久化**——契约不承诺它明天还有效。 */
    val url: String? = null,
)

@Serializable
data class TaskAccepted(
    @SerialName("task_id") val taskId: String,
    val status: String,
    val mode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("mode_changed") val modeChanged: Boolean = false,
    @SerialName("mode_change_reason") val modeChangeReason: String? = null,
    @SerialName("projected_wall_ms") val projectedWallMs: Long? = null,
    @SerialName("sync_budget_ms") val syncBudgetMs: Long? = null,
    @SerialName("policy_version") val policyVersion: String? = null,
    val eventsUrl: String? = null,
    val resultUrl: String? = null,
)

/**
 * 任务快照。字段刻意与调度层的 `schemas/task_snapshot.json` 对齐。
 *
 * 客户端恢复任务的做法是「先取快照，再开事件流补增量」——
 * 这样不依赖长连接先于快照到达，断线重连也只需要补 seq 之后的部分。
 */
@Serializable
data class TaskSnapshot(
    @SerialName("task_id") val taskId: String,
    @SerialName("user_id") val userId: String,
    val status: String,
    val source: String,
    val mode: String,
    val progress: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("parent_task_id") val parentTaskId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    @SerialName("mode_changed") val modeChanged: Boolean = false,
    @SerialName("mode_change_reason") val modeChangeReason: String? = null,
    val plan: PlanSnapshot? = null,
    val artifacts: JsonObject? = null,
    val budget: BudgetSnapshot? = null,
    val clarification: PendingClarification? = null,
    val error: Problem? = null,
    @SerialName("policy_version") val policyVersion: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val links: Links? = null,
) {
    val isTerminal: Boolean get() = status in TERMINAL_STATUSES
    val isAwaitingClarification: Boolean get() = status == STATUS_AWAITING_CLARIFICATION

    companion object {
        const val STATUS_AWAITING_CLARIFICATION = "awaiting_clarification"
        const val STATUS_SUCCEEDED = "succeeded"

        /** 终态不可变：重跑产生新任务，不改写原任务。 */
        val TERMINAL_STATUSES = setOf(
            "succeeded", "failed", "cancelled", "budget_exceeded", "rejected",
        )
    }
}

@Serializable
data class Links(
    val eventsUrl: String? = null,
    val resultUrl: String? = null,
)

@Serializable
data class PlanSnapshot(
    val strategy: String,
    val revision: Int,
    val source: String? = null,
    @SerialName("max_parallelism") val maxParallelism: Int? = null,
    val nodes: List<PlanNode> = emptyList(),
)

@Serializable
data class PlanNode(
    @SerialName("subtask_id") val subtaskId: String,
    val status: String,
    val progress: Double,
    val attempts: Int,
    val name: String? = null,
    val tier: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val error: NodeFailure? = null,
)

@Serializable
data class NodeFailure(
    val code: String,
    val retryable: Boolean,
    val message: String? = null,
    @SerialName("subtask_id") val subtaskId: String? = null,
    @SerialName("on_failure_applied") val onFailureApplied: String? = null,
    val attempts: Int? = null,
)

@Serializable
data class BudgetSnapshot(
    @SerialName("max_cost") val maxCost: Double? = null,
    val spent: Double? = null,
    val currency: String? = null,
    @SerialName("max_wall_ms") val maxWallMs: Long? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long? = null,
    val enforcement: String? = null,
    val warned: Boolean = false,
)

/**
 * 任务停住等人。
 *
 * **这是正常状态，不是异常路径。** 记账场景里「这是支出还是收入」必须问清楚——
 * 猜错的代价是账目被静默污染，用户往往几个月后才发现。
 *
 * [options] 由服务端下发（来自流程模板的 `confirmation` 段），
 * 所以确认页是一个**澄清渲染器**，不是写死的表单。
 */
@Serializable
data class PendingClarification(
    @SerialName("question_id") val questionId: String,
    val question: String,
    val blocking: Boolean,
    val options: List<ClarificationOption> = emptyList(),
    @SerialName("asked_at") val askedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ClarificationOption(
    val id: String,
    val label: String,
)

/**
 * 澄清答复。
 *
 * 字段名与可空性照 `openapi.yaml` 的 `/tasks/{task_id}/clarify` 请求体：
 * `question_id` 必填，`answer_id` / `free_text` / `edits` 都可空。
 *
 * ⚠️ **服务端会下发没有选项的澄清。** 实测中评估器给出的就是一个只有
 * `question`、`options: []` 的问题（「请补充金额和支付方式」）。那种情况下
 * 只能走 [freeText]，界面上必须留一个自由输入的口子，不能只渲染选项按钮。
 *
 * ⚠️ [edits] 与 [TaskFeedback.edits] **形状和含义都不一样**：
 * 这边是会被 `{**prior, **edits}` 合并进 partial_profile 的**键值对对象**，
 * 那边是 `[{field, from, to}]` 的**数组**（自进化的真值标注）。
 * 往这边传数组会让服务端 `TypeError` 直接 500。
 */
@Serializable
data class ClarificationAnswer(
    @SerialName("question_id") val questionId: String,
    /** 选项 id。按下发选项回答时用它；自由文本回答时为 null。 */
    @SerialName("answer_id") val answerId: String? = null,
    /** 自由文本回答。服务端没给选项时唯一可用的路。 */
    @SerialName("free_text") val freeText: String? = null,
    /** 补充画像的键值对，会被合并进 partial_profile。**不是** [FieldEdit] 列表。 */
    val edits: JsonObject? = null,
) {
    init {
        require(!answerId.isNullOrBlank() || !freeText.isNullOrBlank()) {
            "澄清答复必须给出 answer_id 或 free_text，否则等于没回答"
        }
    }
}

@Serializable
data class FieldEdit(
    val field: String,
    val from: String? = null,
    val to: String,
)

/**
 * 人工质量信号。记账场景的自然采集点就是确认/修改页。
 *
 * `verdict` 用枚举而不是字符串：契约里它是**封闭词表**
 * （`accepted` / `edited` / `rejected` / `ignored`），传别的值会被服务端拒。
 * 早先这里写成 `confirmed` —— 那是我照着文档示例猜的，词表里根本没有这个词，
 * 而且端点没有 Pydantic 校验，错值不会报错只会被丢掉。
 */
@Serializable
data class TaskFeedback(
    val verdict: FeedbackVerdict,
    val edits: List<FieldEdit> = emptyList(),
    val reason: String? = null,
)

/** `verdict` 的封闭词表。取值见 `openapi.yaml` 的 `/tasks/{task_id}/feedback`。 */
@Serializable
enum class FeedbackVerdict {
    /** 用户核对无误，直接采纳。 */
    @SerialName("accepted") ACCEPTED,

    /** 用户改过字段。配合 [TaskFeedback.edits] 就是一条带真值的标注。 */
    @SerialName("edited") EDITED,

    /** 用户放弃这笔。 */
    @SerialName("rejected") REJECTED,

    /** 未表态。 */
    @SerialName("ignored") IGNORED,
}

/**
 * 事件流的一帧，**按 SSE 线格式**建模（不是 `schemas/event.json` 里日志记录的形状）。
 *
 * 契约的 SSE 帧把三个字段分散在不同的行上：
 * ```
 * event: subtask.completed
 * id: 7
 * data: {"subtask_id":"extract","output":{...}}
 * ```
 * 所以 [seq] 来自 `id:`、[type] 来自 `event:`、[payload] 来自 `data:`。
 * 按日志记录（`seq`/`task_id`/`type`/`ts`/`data`）建模会解不出来——两者只是字段同源，形状不同。
 *
 * [seq] 既是事件序号也是**重放游标**：重连时把它作为 `Last-Event-ID` 发回去。
 *
 * [payload] 保持为 [JsonObject]：事件类型是开放集合（契约只做加法），
 * 映射到封闭类型会在后端加事件时丢信息或直接崩。
 */
data class TaskEvent(
    val seq: Long,
    val type: String,
    val payload: JsonObject,
) {
    companion object {
        const val TYPE_HEARTBEAT = "heartbeat"
        const val TYPE_CLARIFICATION_NEEDED = "clarification.needed"
        const val TYPE_TOKEN = "token"
        const val TYPE_TASK_COMPLETED = "task.completed"
        const val TYPE_TASK_FAILED = "task.failed"
        const val TYPE_TASK_CANCELLED = "task.cancelled"
        const val TYPE_BUDGET_WARNING = "budget.warning"
        const val TYPE_BUDGET_EXCEEDED = "budget.exceeded"
        const val TYPE_ERROR = "error"
    }
}

/**
 * RFC 9457 形状的结构化错误。
 *
 * 契约硬性要求错误必须**有类型**：调用方靠 [code] 与 [retryable] 决定行为，
 * 不允许把上游异常吞成字符串再拼进正文。
 */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val retryable: Boolean,
    val detail: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("retry_after_ms") val retryAfterMs: Long? = null,
) {
    companion object {
        const val CODE_INVALID_REQUEST = "invalid_request"
        const val CODE_UNSUPPORTED_MEDIA = "unsupported_media"
        const val CODE_MEDIA_TOO_LARGE = "media_too_large"
        const val CODE_IDEMPOTENCY_CONFLICT = "idempotency_conflict"
        const val CODE_NO_CAPABILITY_MATCH = "no_capability_match"
        const val CODE_BUDGET_EXCEEDED = "budget_exceeded"
        const val CODE_POLICY_VIOLATION = "policy_violation"
        const val CODE_HANDLER_ERROR = "handler_error"
        const val CODE_UPSTREAM_LLM_ERROR = "upstream_llm_error"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_CANCELLED = "cancelled"
        const val CODE_RATE_LIMITED = "rate_limited"
        const val CODE_NOT_FOUND = "not_found"
        const val CODE_RESULT_NOT_READY = "result_not_ready"

        /** 上传期就能判定的拒绝，不必等到模型那一跳才失败。 */
        val MIME_PREFLIGHT_CODES = setOf(CODE_UNSUPPORTED_MEDIA, CODE_MEDIA_TOO_LARGE)
    }
}
