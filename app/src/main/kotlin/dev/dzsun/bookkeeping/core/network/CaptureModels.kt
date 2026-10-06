package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.core.money.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `bookkeeping.ReceiptFields`。
 *
 * 契约里这四个 schema **从未定义**，这个形状是拿真实任务实测出来的
 * （跑 `receipt_to_entry`，读 `extract` 节点的 `subtask.completed.output`）。
 * 后端补上定义后，以契约为准核对一次。
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
