package dev.dzsun.bookkeeping.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.ItemDraft
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.network.CaptureClient
import dev.dzsun.bookkeeping.core.network.CaptureOutcome
import dev.dzsun.bookkeeping.core.network.CaptureRequest
import dev.dzsun.bookkeeping.core.network.ClarificationAnswer
import dev.dzsun.bookkeeping.core.network.FeedbackVerdict
import dev.dzsun.bookkeeping.core.network.FieldEdit
import dev.dzsun.bookkeeping.core.network.MediaPayload
import dev.dzsun.bookkeeping.core.network.PendingClarification
import dev.dzsun.bookkeeping.core.network.ReceiptFields
import dev.dzsun.bookkeeping.core.network.TaskFeedback
import dev.dzsun.bookkeeping.core.platform.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 用户视角的三种记法。它们在存储层都是同一个凭证，区别只在分录怎么摆。 */
enum class EntryKind { EXPENSE, INCOME, TRANSFER }

/** 录入页顶部的模式切换：AI 自然语言，或手动表单。 */
enum class EntryMode { AI, MANUAL }

/**
 * 表单上的明细行（「买了什么」）。
 *
 * [amountText] 空 = 不填单价——很多小票只给总额。
 * **明细不参与记账**：金额仍以分录为准，这里也不做「明细之和 == 总额」的校验。
 */
data class ItemLine(
    val description: String = "",
    val amountText: String = "",
)

/**
 * 明细行 → 草稿。空描述的行直接丢掉——它对「几周后想起买了什么」毫无用处。
 * 不在这里校验与总额的关系：折扣、税、抹零都会让两者不等。
 */
fun List<ItemLine>.toItemDrafts(currency: String): List<ItemDraft> = mapNotNull { line ->
    val description = line.description.trim()
    if (description.isEmpty()) return@mapNotNull null
    val amountMinor = line.amountText.trim().takeIf { it.isNotEmpty() }?.let {
        Money.parse(it, currency).amountMinor
    }
    ItemDraft(description = description, amountMinor = amountMinor)
}

/** 一张待确认的解析卡片，可就地编辑后再入账。 */
data class DraftCard(
    val id: String,
    val kind: EntryKind,
    val amountText: String,
    val categoryId: String,
    val payee: String,
    val note: String,
    val dateEpochDay: Long,
    val confidence: Float,
    val error: String? = null,
    /** 发生地点。金额和商户都记不住时的最后一个锚点。 */
    val place: String = "",
    /** 小票明细，纯描述，不参与记账。 */
    val items: List<ItemLine> = emptyList(),
) {
    val isHighConfidence: Boolean get() = confidence >= 0.8f
    val canSave: Boolean get() = amountText.isNotBlank() && categoryId.isNotBlank() && error == null
}

data class AddEntryUiState(
    val mode: EntryMode = EntryMode.AI,
    // —— AI 模式 ——
    val rawText: String = "",
    val isParsing: Boolean = false,
    val isListening: Boolean = false,
    val cards: List<DraftCard> = emptyList(),
    val parseError: String? = null,
    val allSaved: Boolean = false,
    /** 当前这批卡片从哪个渠道来：拍票是 [JournalSource.RECEIPT]，文字/语音是 [JournalSource.VOICE]。 */
    val aiSource: JournalSource = JournalSource.RECEIPT,
    /** 服务端或本地合成的澄清；非空时确认页优先渲染它。 */
    val pendingClarification: PendingClarification? = null,
    /** `CaptureOutcome.NeedsClarification` 带来的任务 id，`clarify`/`feedback` 要用。 */
    val activeTaskId: String? = null,
    /** 用户相对解析基线的改动，入账时按 `edits[]` 回报。 */
    val fieldEdits: List<FieldEdit> = emptyList(),
    // —— 手动模式 ——
    val kind: EntryKind = EntryKind.EXPENSE,
    val amountText: String = "",
    val categoryId: String = "",
    val fromAccountId: String = "",
    val toAccountId: String = "",
    val dateEpochDay: Long = 0L,
    val payee: String = "",
    val note: String = "",
    val place: String = "",
    val items: List<ItemLine> = emptyList(),
    val currency: String = "",
    val expenseCategories: List<AccountEntity> = emptyList(),
    val incomeCategories: List<AccountEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val amountError: String? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
) {
    val visibleCategories: List<AccountEntity>
        get() = if (kind == EntryKind.INCOME) incomeCategories else expenseCategories

    val canSaveManual: Boolean
        get() = amountText.isNotBlank() && currency.isNotBlank() && !isSaving && when (kind) {
            EntryKind.TRANSFER -> fromAccountId.isNotBlank() &&
                toAccountId.isNotBlank() &&
                fromAccountId != toAccountId

            else -> categoryId.isNotBlank() && fromAccountId.isNotBlank()
        }

    val canConfirmCards: Boolean
        get() = cards.isNotEmpty() && cards.all { it.canSave } && !isSaving && pendingClarification == null
}

@HiltViewModel
class AddEntryViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val parser: AiParser,
    private val clarificationPort: ClarificationPort,
    private val captureClient: CaptureClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AddEntryUiState(dateEpochDay = clock.today().toEpochDay()))
    val state: StateFlow<AddEntryUiState> = _state.asStateFlow()

    /** 解析落卡时的原值，用户改动按它算 `edits[].from`。 */
    private val baselines = mutableMapOf<String, DraftCard>()

    init {
        viewModelScope.launch {
            val currency = repository.observeBaseCurrency().first().orEmpty()
            val expenseCategories = repository.accountsOfTypes(listOf(AccountType.EXPENSE))
            val incomeCategories = repository.accountsOfTypes(listOf(AccountType.INCOME))
            val accounts = repository.accountsOfTypes(listOf(AccountType.ASSET, AccountType.LIABILITY))
            _state.update {
                it.copy(
                    currency = currency,
                    expenseCategories = expenseCategories,
                    incomeCategories = incomeCategories,
                    accounts = accounts,
                    categoryId = expenseCategories.firstOrNull()?.id.orEmpty(),
                    fromAccountId = accounts.firstOrNull()?.id.orEmpty(),
                    toAccountId = accounts.getOrNull(1)?.id.orEmpty(),
                )
            }
        }
    }

    // ---------- 模式 ----------

    fun onModeChange(mode: EntryMode) = _state.update { it.copy(mode = mode, parseError = null) }

    // ---------- AI 模式 ----------

    fun onRawTextChange(text: String) = _state.update { it.copy(rawText = text, parseError = null) }

    fun onVoiceClick() {
        // 语音输入依赖系统 RecognizerIntent，这里先做状态占位，
        // 等多模态接口就绪后在 Activity 侧拉起识别并回填 onRawTextChange。
        _state.update { it.copy(isListening = !it.isListening) }
    }

    /**
     * 图片字节就绪后的真正入口。MIME 由调用方给出——契约只收 jpeg/png/webp，
     * HEIF 得先在调用方转成 JPEG。
     */
    fun onPhotoCaptured(bytes: ByteArray, mime: String) {
        _state.update { it.copy(isParsing = true, parseError = null, allSaved = false, aiSource = JournalSource.RECEIPT) }
        viewModelScope.launch {
            runCapture(
                request = CaptureRequest(intent = INTENT_RECEIPT, text = null, media = MediaPayload(bytes, mime)),
                offlineFallback = false,
            )
        }
    }

    /** 图片读取/转码失败。没进调度层，也不该回落本地规则。 */
    fun onCaptureError(message: String) {
        _state.update { it.copy(isParsing = false, parseError = message) }
    }

    fun parseNow() {
        val raw = _state.value.rawText
        if (raw.isBlank()) {
            _state.update { it.copy(parseError = "先说说这笔账，比如「今天打车花了 30」") }
            return
        }
        _state.update { it.copy(isParsing = true, parseError = null, allSaved = false, aiSource = JournalSource.VOICE) }
        viewModelScope.launch {
            runCapture(
                request = CaptureRequest(intent = INTENT_RECEIPT, text = raw),
                offlineFallback = true,
            )
        }
    }

    /**
     * 调度层优先，失败回落 [LocalAiParser]。
     * `offlineFallback` 为 false 时（拍照路径）失败就如实报错——图片没有本地规则可退。
     */
    private suspend fun runCapture(request: CaptureRequest, offlineFallback: Boolean) {
        val today = clock.today().toEpochDay()
        val current = _state.value
        val outcome = runCatching { captureClient.capture(request) }
            .getOrElse { error ->
                if (!offlineFallback) {
                    _state.update { it.copy(isParsing = false, parseError = error.message ?: "识别失败") }
                    return
                }
                parseOffline(request.text.orEmpty(), today, current)
                return
            }

        when (outcome) {
            is CaptureOutcome.Completed -> {
                val card = outcome.fields.toDraftCard(
                    id = "capture-${outcome.taskId}",
                    todayEpochDay = today,
                    expenseCategories = current.expenseCategories,
                    incomeCategories = current.incomeCategories,
                )
                applyCards(listOf(card), taskId = outcome.taskId, clarification = null)
            }

            is CaptureOutcome.NeedsClarification -> {
                // 已抽出的字段先出卡，澄清问题交给渲染器
                val cards = listOfNotNull(
                    outcome.fields?.toDraftCard(
                        id = "capture-${outcome.taskId}",
                        todayEpochDay = today,
                        expenseCategories = current.expenseCategories,
                        incomeCategories = current.incomeCategories,
                    ),
                )
                applyCards(cards, taskId = outcome.taskId, clarification = outcome.clarification)
            }

            is CaptureOutcome.Failed -> {
                // 契约要求保留已完成节点的产物，别让用户白拍
                val partial = outcome.partialFields?.toDraftCard(
                    id = "partial-${outcome.taskId}",
                    todayEpochDay = today,
                    expenseCategories = current.expenseCategories,
                    incomeCategories = current.incomeCategories,
                )
                val message = outcome.problem?.detail ?: outcome.problem?.title ?: "识别失败"
                if (partial != null) {
                    applyCards(listOf(partial), taskId = outcome.taskId, clarification = null, error = message)
                } else {
                    _state.update { it.copy(isParsing = false, parseError = message) }
                }
            }
        }
    }

    private suspend fun parseOffline(raw: String, today: Long, current: AddEntryUiState) {
        val parsed = runCatching { parser.parse(raw) }
            .getOrElse {
                _state.update { it.copy(isParsing = false, parseError = "解析失败，换种说法试试") }
                return
            }
        if (parsed.isEmpty()) {
            _state.update {
                it.copy(isParsing = false, parseError = "没听懂金额，试试「午饭 18 元」这样的说法")
            }
            return
        }
        val cards = parsed.map { p ->
            DraftCard(
                id = p.id,
                kind = p.kind,
                amountText = p.amountText,
                categoryId = matchCategoryId(p, current),
                payee = p.payee,
                note = p.note,
                dateEpochDay = today + p.dateOffsetDays,
                confidence = p.confidence,
            )
        }
        applyCards(cards, taskId = null, clarification = localClarification(cards))
    }

    private fun applyCards(
        cards: List<DraftCard>,
        taskId: String?,
        clarification: PendingClarification?,
        error: String? = null,
    ) {
        baselines.clear()
        cards.forEach { baselines[it.id] = it }
        _state.update {
            it.copy(
                isParsing = false,
                cards = cards,
                fieldEdits = emptyList(),
                pendingClarification = clarification,
                activeTaskId = taskId,
                parseError = error,
            )
        }
    }

    private fun matchCategoryId(parsed: ParsedEntry, state: AddEntryUiState): String {
        val pool = if (parsed.kind == EntryKind.INCOME) state.incomeCategories else state.expenseCategories
        return pool.firstOrNull { it.name == parsed.categoryName }?.id
            ?: pool.firstOrNull()?.id.orEmpty()
    }

    fun onCardAmountChange(id: String, text: String) = updateCard(id) {
        it.copy(amountText = text, error = null)
    }

    fun onCardCategoryChange(id: String, categoryId: String) = updateCard(id) {
        it.copy(categoryId = categoryId, error = null)
    }

    fun onCardPayeeChange(id: String, text: String) = updateCard(id) { it.copy(payee = text) }

    fun onCardNoteChange(id: String, text: String) = updateCard(id) { it.copy(note = text) }

    fun onCardDateChange(id: String, epochDay: Long) = updateCard(id) { it.copy(dateEpochDay = epochDay) }

    fun onCardPlaceChange(id: String, text: String) = updateCard(id) { it.copy(place = text) }

    fun onCardItemChange(id: String, index: Int, line: ItemLine) = updateCard(id) { card ->
        card.copy(items = card.items.mapIndexed { i, existing -> if (i == index) line else existing })
    }

    fun onCardItemAdd(id: String) = updateCard(id) {
        it.copy(items = it.items + ItemLine())
    }

    fun onCardItemRemove(id: String, index: Int) = updateCard(id) { card ->
        card.copy(items = card.items.filterIndexed { i, _ -> i != index })
    }

    fun onCardRemove(id: String) = _state.update { s ->
        baselines.remove(id)
        s.copy(
            cards = s.cards.filterNot { it.id == id },
            pendingClarification = localClarification(s.cards.filterNot { it.id == id }),
        )
    }

    private companion object {
        /** 契约 taxonomy 里的意图串，拍票据/文字记账都走它。 */
        const val INTENT_RECEIPT = "bookkeeping.capture_from_receipt"
    }

    private fun updateCard(id: String, transform: (DraftCard) -> DraftCard) {
        _state.update { s ->
            val cards = s.cards.map { if (it.id == id) transform(it) else it }
            s.copy(cards = cards, fieldEdits = recomputeEdits(cards))
        }
    }

    private fun recomputeEdits(cards: List<DraftCard>): List<FieldEdit> = cards.flatMap { card ->
        val baseline = baselines[card.id] ?: return@flatMap emptyList()
        collectFieldEdits(baseline, card)
    }

    /**
     * 用户答复澄清后落地。
     *
     * 本地合成的选项直接改卡片；服务端的回 [ClarificationAnswer]。
     * 服务端可能下发**没有选项**的澄清，那时走 [freeText]——契约里
     * `answer_id` 与 `free_text` 都可空，但至少给一个，否则等于没回答。
     */
    fun onClarificationAnswer(optionId: String? = null, freeText: String? = null) {
        val current = _state.value
        val clarification = current.pendingClarification ?: return
        val answer = buildClarificationAnswer(clarification.questionId, optionId, freeText) ?: return
        // 本地合成的 kind 澄清：选项直接决定方向
        optionId?.let { kindFromOptionId(it) }?.let { kind ->
            val id = clarification.questionId.removePrefix("local-")
            _state.update { s ->
                val cards = s.cards.map {
                    if (it.id == id) it.copy(kind = kind, error = null) else it
                }
                s.copy(cards = cards, fieldEdits = recomputeEdits(cards))
            }
        }
        val taskId = current.activeTaskId
        _state.update { it.copy(pendingClarification = null) }
        viewModelScope.launch {
            runCatching { clarificationPort.answer(taskId, answer) }
        }
    }

    fun confirmCards() {
        val current = _state.value
        if (!current.canConfirmCards) return

        val accountId = current.fromAccountId
        if (accountId.isBlank()) {
            _state.update { it.copy(parseError = "还没有可用账户，去设置里先建一个") }
            return
        }

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                current.cards.forEach { card ->
                    val amount = Money.parse(card.amountText, current.currency.ifBlank { "CNY" })
                    if (amount.isZero()) error("金额不能为零")
                    val categoryPool =
                        if (card.kind == EntryKind.INCOME) current.incomeCategories else current.expenseCategories
                    val categoryId = card.categoryId.ifBlank {
                        categoryPool.firstOrNull()?.id ?: error("缺少分类")
                    }
                    val itemDrafts = card.items.toItemDrafts(current.currency.ifBlank { "CNY" })
                    val draft = when (card.kind) {
                        EntryKind.INCOME -> JournalDraft.income(
                            dateEpochDay = card.dateEpochDay,
                            amount = amount,
                            toAccountId = accountId,
                            categoryAccountId = categoryId,
                            payee = card.payee.ifBlank { null },
                            note = card.note.ifBlank { null },
                            source = current.aiSource,
                        )

                        else -> JournalDraft.expense(
                            dateEpochDay = card.dateEpochDay,
                            amount = amount,
                            fromAccountId = accountId,
                            categoryAccountId = categoryId,
                            payee = card.payee.ifBlank { null },
                            note = card.note.ifBlank { null },
                            source = current.aiSource,
                        )
                    }.copy(
                        place = card.place.trim().ifBlank { null },
                        items = itemDrafts,
                    )
                    repository.post(draft)
                }
            }
            result.fold(
                onSuccess = {
                    // 修正反馈：有改动就是 edited，没动是 confirmed——那是自进化最值钱的标注
                    val edits = current.fieldEdits
                    runCatching {
                        clarificationPort.feedback(
                            current.activeTaskId,
                            TaskFeedback(
                                verdict = if (edits.isEmpty()) FeedbackVerdict.ACCEPTED else FeedbackVerdict.EDITED,
                                edits = edits,
                            ),
                        )
                    }
                    baselines.clear()
                    _state.update {
                        it.copy(
                            isSaving = false,
                            allSaved = true,
                            cards = emptyList(),
                            rawText = "",
                            fieldEdits = emptyList(),
                            pendingClarification = null,
                            activeTaskId = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(isSaving = false, parseError = error.message ?: "入账失败")
                    }
                },
            )
        }
    }

    // ---------- 手动模式（保留原表单能力） ----------

    fun onKindChange(kind: EntryKind) = _state.update { current ->
        val categoryId = when (kind) {
            EntryKind.INCOME -> current.incomeCategories.firstOrNull()?.id.orEmpty()
            EntryKind.EXPENSE -> current.expenseCategories.firstOrNull()?.id.orEmpty()
            EntryKind.TRANSFER -> current.categoryId
        }
        current.copy(kind = kind, categoryId = categoryId, amountError = null)
    }

    fun onAmountChange(text: String) = _state.update { it.copy(amountText = text, amountError = null) }

    fun onCategoryChange(id: String) = _state.update { it.copy(categoryId = id) }

    fun onFromAccountChange(id: String) = _state.update { it.copy(fromAccountId = id) }

    fun onToAccountChange(id: String) = _state.update { it.copy(toAccountId = id) }

    fun onDateChange(epochDay: Long) = _state.update { it.copy(dateEpochDay = epochDay) }

    fun onPayeeChange(text: String) = _state.update { it.copy(payee = text) }

    fun onNoteChange(text: String) = _state.update { it.copy(note = text) }

    fun onPlaceChange(text: String) = _state.update { it.copy(place = text) }

    fun onItemChange(index: Int, line: ItemLine) = _state.update { s ->
        s.copy(items = s.items.mapIndexed { i, existing -> if (i == index) line else existing })
    }

    fun onItemAdd() = _state.update { it.copy(items = it.items + ItemLine()) }

    fun onItemRemove(index: Int) = _state.update { s ->
        s.copy(items = s.items.filterIndexed { i, _ -> i != index })
    }

    fun saveManual() {
        val current = _state.value
        if (!current.canSaveManual) return

        val amount = runCatching { Money.parse(current.amountText, current.currency) }
            .getOrElse { error ->
                _state.update { it.copy(amountError = error.message ?: "金额无法解析") }
                return
            }
        if (amount.isZero()) {
            _state.update { it.copy(amountError = "金额不能为零") }
            return
        }

        val itemDrafts = current.items.toItemDrafts(current.currency.ifBlank { "CNY" })
        val draft = when (current.kind) {
            EntryKind.EXPENSE -> JournalDraft.expense(
                dateEpochDay = current.dateEpochDay,
                amount = amount,
                fromAccountId = current.fromAccountId,
                categoryAccountId = current.categoryId,
                payee = current.payee.ifBlank { null },
                note = current.note.ifBlank { null },
            )

            EntryKind.INCOME -> JournalDraft.income(
                dateEpochDay = current.dateEpochDay,
                amount = amount,
                toAccountId = current.fromAccountId,
                categoryAccountId = current.categoryId,
                payee = current.payee.ifBlank { null },
                note = current.note.ifBlank { null },
            )

            EntryKind.TRANSFER -> JournalDraft.transfer(
                dateEpochDay = current.dateEpochDay,
                amount = amount,
                fromAccountId = current.fromAccountId,
                toAccountId = current.toAccountId,
                note = current.note.ifBlank { null },
            )
        }.copy(
            place = current.place.trim().ifBlank { null },
            items = itemDrafts,
        )

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runCatching { repository.post(draft) }
                .onSuccess { _state.update { it.copy(isSaving = false, saved = true) } }
                .onFailure { error ->
                    _state.update { it.copy(isSaving = false, amountError = error.message ?: "保存失败") }
                }
        }
    }
}
