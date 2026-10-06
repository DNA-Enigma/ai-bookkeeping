package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.network.ClarificationAnswer
import dev.dzsun.bookkeeping.core.network.ClarificationOption
import dev.dzsun.bookkeeping.core.network.FieldEdit
import dev.dzsun.bookkeeping.core.network.PendingClarification
import dev.dzsun.bookkeeping.core.network.TaskFeedback

/**
 * 澄清答复出口。渲染器只依赖这个接口，不直接碰 `DispatcherClient`——
 * 接调度层时在 `AppModule` 换绑定即可，与 `AiParser` 同一套路。
 */
interface ClarificationPort {
    suspend fun answer(answer: ClarificationAnswer)
    suspend fun feedback(feedback: TaskFeedback)
}

/** 离线占位：无调度层时只收集不上传，保证确认页流程能走通。 */
object NoOpClarificationPort : ClarificationPort {
    override suspend fun answer(answer: ClarificationAnswer) = Unit
    override suspend fun feedback(feedback: TaskFeedback) = Unit
}

/** `edits[].field` 取值，与 [DraftCard] 字段一一对应。 */
object EditFields {
    const val KIND = "kind"
    const val AMOUNT = "amount"
    const val CATEGORY = "category"
    const val PAYEE = "payee"
    const val NOTE = "note"
    const val DATE = "date"
}

/**
 * 对比解析基线与用户改后的卡片，产出 `edits[{field, from, to}]`。
 * 没动过的字段不进列表——那是自进化要的真值标注，噪声会稀释它。
 */
fun collectFieldEdits(baseline: DraftCard, current: DraftCard): List<FieldEdit> {
    val edits = mutableListOf<FieldEdit>()
    if (baseline.kind != current.kind) {
        edits += FieldEdit(EditFields.KIND, baseline.kind.name, current.kind.name)
    }
    if (baseline.amountText != current.amountText) {
        edits += FieldEdit(EditFields.AMOUNT, baseline.amountText, current.amountText)
    }
    if (baseline.categoryId != current.categoryId) {
        edits += FieldEdit(EditFields.CATEGORY, baseline.categoryId, current.categoryId)
    }
    if (baseline.payee != current.payee) {
        edits += FieldEdit(EditFields.PAYEE, baseline.payee, current.payee)
    }
    if (baseline.note != current.note) {
        edits += FieldEdit(EditFields.NOTE, baseline.note, current.note)
    }
    if (baseline.dateEpochDay != current.dateEpochDay) {
        edits += FieldEdit(EditFields.DATE, baseline.dateEpochDay.toString(), current.dateEpochDay.toString())
    }
    return edits
}

/**
 * 离线降级：低置信度卡片合成一条本地 `PendingClarification`，
 * 形状与服务端 `confirmation` 段下发的完全一致，渲染器无感切换。
 */
fun localClarification(cards: List<DraftCard>): PendingClarification? {
    val low = cards.filter { !it.isHighConfidence }
    if (low.isEmpty()) return null
    val first = low.first()
    return PendingClarification(
        questionId = "local-${first.id}",
        question = "「${first.note.trim().take(24)}」这笔是收入还是支出？",
        blocking = true,
        options = listOf(
            ClarificationOption("expense", "支出"),
            ClarificationOption("income", "收入"),
        ),
    )
}

/** 选项 id → [EntryKind]，仅本地合成的澄清使用。服务端下发的选项不走这个映射。 */
fun kindFromOptionId(optionId: String): EntryKind? = when (optionId) {
    "expense" -> EntryKind.EXPENSE
    "income" -> EntryKind.INCOME
    else -> null
}
