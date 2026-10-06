package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.core.money.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `bookkeeping.ReceiptFields` —— `extract_receipt_fields` 节点的产出。
 *
 * 形状与 `schemas/bookkeeping_receipt_fields.json` 对齐（该 schema 已冻结）。
 *
 * [amount] 刻意是 [JsonElement] 而不是 `Double`：金额一旦经过浮点就再也回不到精确值。
 * 用 [money] 取值，它走 JSON 字面量直接转 [BigDecimal]。
 */
@Serializable
data class ReceiptFields(
    val amount: JsonElement? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val datetime: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    val direction: String? = null,
    /** 归类，取值来自用户自己的分类词表（不是代码里的常量）。 */
    val category: String? = null,
    val confidence: Double? = null,
    /**
     * 字段级置信度，如 `{"amount": 0.95, "merchant": 0.8}`。
     *
     * ⚠️ **当前服务端并不产出这个字段**（`schemas/bookkeeping_receipt_fields.json`
     * 里没有，`grep field_confidence` 在 dispatcher 全仓为空）。这里按可空透出，
     * 是照 PM 定死的界面契约先留好口子——真加上了就能直接用，没有也不影响。
     */
    @SerialName("field_confidence") val fieldConfidence: Map<String, Float>? = null,
    val notes: String? = null,
) {
    /** 金额，转成整数最小单位的 [Money]；缺金额或币种时返回 null。 */
    fun money(): Money? {
        val literal = amount?.jsonPrimitive?.content ?: return null
        val code = currency?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Money.parse(literal, code) }.getOrNull()
    }

    val isExpense: Boolean get() = direction != DIRECTION_INCOME

    companion object {
        const val DIRECTION_INCOME = "income"
    }
}

/**
 * `bookkeeping.LedgerEntry` —— 调度层给出的**待入账凭证草稿**，不是已写入的账目。
 *
 * 形状与 `schemas/bookkeeping_ledger_entry.json`（已冻结）及实跑一致：
 * `{entry_id: "task_…:main", amount: 38.0, currency: "CNY", direction: "expense",
 *   category: "餐饮", merchant: null, occurred_at: null, source_task: "task_…", note: null}`
 *
 * 契约的口径值得记住：后端 handler 跑在服务端，而账本在本地 Room——
 * 它**不可能**写进用户手机。所以是「单式过网、复式留在消费端」：
 * 服务端只给方向与分类名，借贷映射在本机由 `JournalDraft` 完成。
 *
 * [entryId] 由 `(task_id, subtask_id)` 派生，稳定——重试时拿它做幂等键就不会重复入账。
 */
@Serializable
data class LedgerEntry(
    @SerialName("entry_id") val entryId: String? = null,
    val amount: JsonElement? = null,
    val currency: String? = null,
    val merchant: String? = null,
    val category: String? = null,
    val direction: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("source_task") val sourceTask: String? = null,
    val note: String? = null,
) {
    /** 折算成确认卡片要的那种字段视图。没提到的字段留空，不编造。 */
    fun toReceiptFields(): ReceiptFields = ReceiptFields(
        amount = amount,
        currency = currency,
        merchant = merchant,
        datetime = occurredAt,
        direction = direction,
        category = category,
        // 服务端没有给整体置信度，这里不编一个——留给界面按「无置信度」处理
        confidence = null,
        notes = note,
    )

    companion object {
        /**
         * 「这个 JSON 对象是不是一条 LedgerEntry」的判别键。
         * schema 里 `entry_id` 是必填，其余字段都可空，所以只有它能做判别。
         */
        const val KEY_ENTRY_ID = "entry_id"
    }
}

/** `bookkeeping.DedupeResult`，同样是实测形状。 */
@Serializable
data class DedupeResult(
    val duplicate: Boolean = false,
    @SerialName("matched_entry_id") val matchedEntryId: String? = null,
)

/**
 * 一次捕获的终局。
 *
 * [Failed] 也带 [partialFields]：契约明写「已完成节点的产物保留并如实上报——
 * 一张已抽取好字段的票据截图，即使最终没有入账，它的抽取结果也是有价值的」。
 * 实测也确实如此：`extract` 抽对了字段，却因为下游 `normalize` 超时而整单失败。
 * 把产物丢掉等于让用户白拍一张。
 */
sealed interface CaptureOutcome {
    val taskId: String

    data class NeedsClarification(
        override val taskId: String,
        val clarification: PendingClarification,
        val fields: ReceiptFields?,
    ) : CaptureOutcome

    data class Completed(
        override val taskId: String,
        val fields: ReceiptFields,
        /**
         * 服务端真正写库的那条记录。**可选**：不是每条路由都会给
         * （实测 `single_tool_action` 会给 `artifacts.main.entry`，与 [fields] 同源）。
         */
        val entry: LedgerEntry? = null,
        /**
         * 整体识别置信度，**null 表示拿不到**。
         *
         * 拿不到有三种来路，都不是异常：老服务端不给；走 `single_tool_action`
         * 的纯文本路由产物里根本没有这个字段；事件流断了、客户端只回看到
         * `LedgerEntry`（那种产物只带方向与分类，不带置信度）。
         *
         * 消费方（见 `core/ledger/ConfidenceGate`）**必须把 null 当成"不可信"**，
         * 而不是"默认可信"——不知道可不可信的时候多问一句，远比记错一笔便宜。
         */
        val confidence: Float? = null,
        /** 字段级置信度。服务端暂未产出，见 [ReceiptFields.fieldConfidence]。 */
        val fieldConfidence: Map<String, Float>? = null,
    ) : CaptureOutcome

    data class Failed(
        override val taskId: String,
        val problem: Problem?,
        val partialFields: ReceiptFields?,
    ) : CaptureOutcome

    companion object {
        /**
         * 快照 → 终局。**所有终态都从这里出**，不在「事件类型」与「快照状态」两处各判一次，
         * 否则两处口径一漂就会给出互相矛盾的结论。
         *
         * [fields] 是事件流里已经攒到的抽取产物。它比快照先到，而且在任务最终失败时
         * 往往**是唯一还在的东西**——契约明写「已完成节点的产物保留并如实上报」，
         * 服务端也确实这么做了（`runner.py` 收集所有产出过输出的节点，与任务成败无关）。
         * 所以失败分支必须把它带上：丢掉等于让用户白拍一张。
         */
        internal fun fromSnapshot(
            taskId: String,
            snapshot: TaskSnapshot?,
            fields: ReceiptFields?,
        ): CaptureOutcome {
            if (snapshot == null) {
                return Failed(
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
                // 顺序要紧：**先抽取产物、后凭证**。票据流程的快照里两者同时存在，
                // 而凭证不带置信度——先取凭证就会把 `confidence` 丢掉，
                // 偏偏那条路（事件流断了只能回看快照）最该问用户一句。
                val resolved = fields
                    ?: snapshot.artifacts.receiptFields()
                    ?: entry?.toReceiptFields()
                return if (resolved == null) {
                    Failed(
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
                    // 置信度跟着 [resolved] 走：事件流那条路拿到的 extract 产物带它，
                    // 回看快照那条路若只找到 LedgerEntry（无置信度）就给 null——
                    // 宁可让消费方多问一句，也不要在这里编一个数出来
                    Completed(
                        taskId = taskId,
                        fields = resolved,
                        entry = entry,
                        confidence = resolved.confidence?.toFloat(),
                        fieldConfidence = resolved.fieldConfidence,
                    )
                }
            }

            snapshot.clarification?.let {
                if (snapshot.isAwaitingClarification) {
                    return NeedsClarification(taskId, it, fields)
                }
            }

            // 失败分支**同样**要翻快照里的产物。[fields] 来自事件流，只在流活着时才有；
            // 流断在半路、或失败发生在客户端连上之前时它是 null，而快照里 extract 的产物
            // 可能好好地在那儿。只认 [fields] 就会把它们丢掉——那正是「用户白拍一张」。
            val partial = fields
                ?: snapshot.artifacts.ledgerEntry()?.toReceiptFields()
                ?: snapshot.artifacts.receiptFields()
            return Failed(taskId, snapshot.error, partial)
        }
    }
}

/** 请求一次捕获。文字与媒体至少给一个。 */
data class CaptureRequest(
    val intent: String,
    val text: String? = null,
    val media: MediaPayload? = null,
) {
    init {
        require(!text.isNullOrBlank() || media != null) { "捕获请求必须带文字或媒体" }
    }
}

/** 媒体与它的 MIME 绑在一起，避免「传了字节却没给类型」这种半截状态。 */
data class MediaPayload(
    val bytes: ByteArray,
    val mime: String,
    val role: MediaRole = MediaRole.SCREENSHOT,
)

/** 从事件载荷里取指定节点的产出。事件是开放集合，所以每一步都容错。 */
internal fun JsonObject?.subtaskOutput(subtaskId: String): JsonObject? {
    val node = this ?: return null
    if (node["subtask_id"]?.jsonPrimitive?.content != subtaskId) return null
    return node["output"]?.jsonObject
}

/** 契约回包的解码器。事件与产物都是开放对象，多出来的键一律忽略。 */
internal val CaptureJson = Json { ignoreUnknownKeys = true }

/** 先自己、再逐层往里，产出所有嵌套 JSON 对象。 */
internal fun JsonObject.nestedObjects(): Sequence<JsonObject> = sequence {
    yield(this@nestedObjects)
    for ((_, value) in this@nestedObjects) {
        val child = value as? JsonObject ?: continue
        yieldAll(child.nestedObjects())
    }
}

/**
 * 终态快照的 `artifacts` 里，调度层给的那条待入账凭证。
 *
 * **形状随路由而变**，实测两种（都是节点产物直接就是一条凭证，没有 `entry` 再包一层）：
 * - 票据流程按节点分：`{extract:{…}, normalize:{…}, deduce:{…}, write:{entry_id:…}}`
 * - 单步工具只有：`{main:{entry_id:…}}`
 *
 * 所以按判别键 `entry_id` 递归找，而不是假定某个固定键——写死取 `artifacts["extract"]`
 * 或找 `entry` 包装键，两种形状都会误判成「成功但没产物」。
 */
internal fun JsonObject?.ledgerEntry(): LedgerEntry? =
    this?.nestedObjects()
        ?.filter { it.containsKey(LedgerEntry.KEY_ENTRY_ID) }
        ?.firstNotNullOfOrNull { decodeOrNull(LedgerEntry.serializer(), it) }

/**
 * 抽取出来的字段（`extract` 节点的产物），**不包括凭证对象**。
 *
 * 判别方式是有没有 `entry_id`：凭证（`write` / `main`）一定有，
 * 抽取产物一定没有。这条区分要紧——**置信度只有抽取产物才有**。
 * 票据流程的快照里 `extract` 与 `write` 同时存在，若把凭证也算进"抽取字段"，
 * 取到凭证就会把 `confidence: 0.95` 丢掉（凭证里没有这个字段），
 * 而那条路正是「事件流断了、只能回看快照」——最该问用户一句的时候。
 *
 * 凭证那条路另有 [LedgerEntry.toReceiptFields]，它会把 `occurred_at` 映射成
 * `datetime`；两者分工明确，不互相顶替。
 */
internal fun JsonObject?.receiptFields(): ReceiptFields? =
    this?.nestedObjects()
        ?.filter { it.containsKey("amount") && it.containsKey("currency") }
        ?.filterNot { it.containsKey(LedgerEntry.KEY_ENTRY_ID) }
        ?.firstNotNullOfOrNull { decodeOrNull(ReceiptFields.serializer(), it) }

private fun <T> decodeOrNull(
    serializer: kotlinx.serialization.DeserializationStrategy<T>,
    element: JsonObject,
): T? = runCatching { CaptureJson.decodeFromJsonElement(serializer, element) }.getOrNull()
