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
    val notes: String? = null,
) {
    /** 金额，转成整数最小单位的 [Money]；缺金额或币种时返回 null。 */
    fun money(): Money? {
        val literal = amount?.jsonPrimitive?.content ?: return null
        val code = currency?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Money.parse(literal, code) }.getOrNull()
    }

    val isExpense: Boolean get() = direction != DIRECTION_INCOME
    val isHighConfidence: Boolean get() = (confidence ?: 0.0) >= CONFIDENCE_THRESHOLD

    companion object {
        const val DIRECTION_INCOME = "income"
        const val CONFIDENCE_THRESHOLD = 0.8
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
    ) : CaptureOutcome

    data class Failed(
        override val taskId: String,
        val problem: Problem?,
        val partialFields: ReceiptFields?,
    ) : CaptureOutcome
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

/** 退路：有些路由把字段直接摊在节点产物上，没有 `entry_id` 可判别。 */
internal fun JsonObject?.receiptFields(): ReceiptFields? =
    this?.nestedObjects()
        ?.filter { it.containsKey("amount") && it.containsKey("currency") }
        ?.firstNotNullOfOrNull { decodeOrNull(ReceiptFields.serializer(), it) }

private fun <T> decodeOrNull(
    serializer: kotlinx.serialization.DeserializationStrategy<T>,
    element: JsonObject,
): T? = runCatching { CaptureJson.decodeFromJsonElement(serializer, element) }.getOrNull()
