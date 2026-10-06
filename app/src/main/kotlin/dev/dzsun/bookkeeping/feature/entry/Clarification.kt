package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.network.ClarificationAnswer
import dev.dzsun.bookkeeping.core.network.ClarificationOption
import dev.dzsun.bookkeeping.core.network.FieldEdit
import dev.dzsun.bookkeeping.core.network.PendingClarification
import dev.dzsun.bookkeeping.core.network.ReceiptFields
import dev.dzsun.bookkeeping.core.network.TaskFeedback
import java.time.LocalDate

/**
 * 澄清答复出口。渲染器只依赖这个接口，不直接碰 `DispatcherClient`——
 * 接调度层时在 [ClarificationModule] 换绑定即可，与 `AiParser` 同一套路。
 *
 * [taskId] 来自 `CaptureOutcome.NeedsClarification`；纯本地流程没有 id 时传 null。
 */
interface ClarificationPort {
    suspend fun answer(taskId: String?, answer: ClarificationAnswer)
    suspend fun feedback(taskId: String?, feedback: TaskFeedback)
}

/** 离线占位：无调度层时只收集不上传，保证确认页流程能走通。 */
object NoOpClarificationPort : ClarificationPort {
    override suspend fun answer(taskId: String?, answer: ClarificationAnswer) = Unit
    override suspend fun feedback(taskId: String?, feedback: TaskFeedback) = Unit
}

/** `edits[].field` 取值，与 [DraftCard] 字段一一对应。 */
object EditFields {
    const val KIND = "kind"
    const val AMOUNT = "amount"
    const val CATEGORY = "category"
    const val PAYEE = "payee"
    const val NOTE = "note"
    const val DATE = "date"
    const val PLACE = "place"
    const val ITEMS = "items"
}

/**
 * 明细改动的文本形态，进 `edits[].from/to`。
 * 格式：`描述:金额|描述:金额`；没填金额的行只留描述。
 */
fun List<ItemLine>.toEditText(): String =
    filter { it.description.isNotBlank() }
        .joinToString("|") { "${it.description.trim()}:${it.amountText.trim()}" }

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
    if (baseline.place != current.place) {
        edits += FieldEdit(EditFields.PLACE, baseline.place, current.place)
    }
    if (baseline.items != current.items) {
        edits += FieldEdit(EditFields.ITEMS, baseline.items.toEditText(), current.items.toEditText())
    }
    return edits
}

/**
 * 离线降级：低置信度卡片合成一条本地 [PendingClarification]，
 * 形状与服务端 `confirmation` 段下发的完全一致，渲染器无感切换。
 *
 * [threshold] 与自动入账用的是**同一个**阈值：两处各定一个的话，会出现
 * 「本地认为该问、自动入账却直接放了」这种自相矛盾的状态。
 */
fun localClarification(cards: List<DraftCard>, threshold: Float): PendingClarification? {
    val low = cards.filter { !it.isHighConfidence(threshold) }
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

/**
 * 能否提交澄清答复。
 *
 * 有选项时必须选一个；**没有选项时只能靠自由文本**——
 * 服务端实测会下发 `options: []` 的澄清，那时按钮若还绑在选项上就是死路。
 */
fun canAnswerClarification(
    options: List<ClarificationOption>,
    optionId: String,
    freeText: String,
): Boolean = if (options.isEmpty()) freeText.isNotBlank() else optionId.isNotBlank()

/** 把用户的选项/自由文本组装成契约答复。两者都空时返回 null——等于没回答。 */
fun buildClarificationAnswer(
    questionId: String,
    optionId: String?,
    freeText: String?,
): ClarificationAnswer? {
    val id = optionId?.takeIf { it.isNotBlank() }
    val text = freeText?.trim()?.takeIf { it.isNotEmpty() }
    if (id == null && text == null) return null
    return ClarificationAnswer(questionId = questionId, answerId = id, freeText = text)
}

/**
 * 调度层抽出来的票据字段 → 确认卡。金额走 [ReceiptFields.money]，不经过浮点。
 * 分类按名字从科目池匹配，匹配不上留空由用户选——分类是数据不是代码。
 *
 * [fieldConfidence] 是契约里的分字段置信度（服务端当前恒不产出）。有它时
 * 挑出最低的那个字段，确认卡上提示用户重点核对——没有就不提示，不编。
 */
fun ReceiptFields.toDraftCard(
    id: String,
    todayEpochDay: Long,
    expenseCategories: List<dev.dzsun.bookkeeping.core.database.AccountEntity>,
    incomeCategories: List<dev.dzsun.bookkeeping.core.database.AccountEntity>,
    fieldConfidence: Map<String, Float>? = null,
): DraftCard {
    val kind = if (isExpense) EntryKind.EXPENSE else EntryKind.INCOME
    val pool = if (kind == EntryKind.INCOME) incomeCategories else expenseCategories
    val amountText = money()?.toPlainString().orEmpty()
    return DraftCard(
        id = id,
        kind = kind,
        amountText = amountText,
        categoryId = pool.firstOrNull()?.id.orEmpty(),
        payee = merchant.orEmpty(),
        note = notes.orEmpty(),
        dateEpochDay = parseDateEpochDay(datetime) ?: todayEpochDay,
        // 如实传：拿不到就是 null，别在这里补成 0——补了以后就分不出
        // 「识别得很勉强」和「压根没给置信度」
        confidence = confidence?.toFloat(),
        weakestField = weakestFieldName(fieldConfidence),
    )
}

/**
 * 分字段置信度里最低的那个字段名。地图为空或没有条目时返回 null——
 * **没有就是没有**，不拿整体置信度冒充字段级提示。
 */
fun weakestFieldName(fieldConfidence: Map<String, Float>?): String? =
    fieldConfidence?.entries?.minByOrNull { it.value }?.key

private fun parseDateEpochDay(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    // 契约的 datetime 是 ISO 串，这里只认日期前缀，认不出就交回默认值
    return runCatching { LocalDate.parse(raw.take(10)).toEpochDay() }.getOrNull()
}
