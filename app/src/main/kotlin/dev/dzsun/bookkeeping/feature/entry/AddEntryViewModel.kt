package dev.dzsun.bookkeeping.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.ItemDraft
import dev.dzsun.bookkeeping.core.ledger.ConfidenceGate
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
import dev.dzsun.bookkeeping.core.network.UserFacingErrors
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
    /**
     * 识别置信度。**null 表示拿不到**（老服务端不给、或事件流断了只能回看快照），
     * 不是「置信度为 0」——两者的处理一样（都走确认卡），但含义不同，
     * 混成一个数以后就再也分不出来了。
     */
    val confidence: Float? = null,
    val error: String? = null,
    /** 发生地点。金额和商户都记不住时的最后一个锚点。 */
    val place: String = "",
    /** 小票明细，纯描述，不参与记账。 */
    val items: List<ItemLine> = emptyList(),
    /**
     * 分字段置信度里最低的字段名，确认卡上提示「重点核对它」。
     * 服务端当前不产出 `field_confidence`，所以这个值现在多半是 null——
     * **没有就不提示**，不拿整体置信度冒充。
     */
    val weakestField: String? = null,
) {
    /**
     * 达阈值才算高置信。阈值由 [AutoConfirmSettings] 提供、用户在设置页可调，
     * 所以是函数而不是属性——写成属性就得把阈值写死在数据类里。
     */
    fun isHighConfidence(threshold: Float): Boolean = ConfidenceGate.canAutoPost(confidence, threshold)

    val canSave: Boolean get() = amountText.isNotBlank() && categoryId.isNotBlank() && error == null
}

/**
 * 自动入账之后给用户看的那一条。
 *
 * [undoAvailable] 为 false 时**不渲染撤销按钮**：那说明数据层还没有作废接口
 * （见 [AutoEntryUndo]）。显示一个点不动的按钮，比不显示更让人恼火。
 */
data class AutoSavedNotice(
    val journalId: String?,
    val label: String,
    val undoAvailable: Boolean,
)

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
    /**
     * 高置信自动入账后的交代。非空时界面显示一条「已入账 ¥38.50 星巴克 · 撤销」。
     * 与 [saved]（手动保存后的全屏动效）分开：那条路用户按过按钮，这条没按过，
     * 给他一个明确的反悔口子才算交代清楚。
     */
    val autoSaved: AutoSavedNotice? = null,
    /** 自动入账阈值。界面只读，来源是 [AutoConfirmSettings]。 */
    val autoConfirmThreshold: Float = ConfidenceGate.DEFAULT_THRESHOLD,
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
    private val autoConfirmSettings: AutoConfirmSettings,
    private val autoEntryUndo: AutoEntryUndo,
) : ViewModel() {

    private val _state = MutableStateFlow(AddEntryUiState(dateEpochDay = clock.today().toEpochDay()))
    val state: StateFlow<AddEntryUiState> = _state.asStateFlow()

    /** 解析落卡时的原值，用户改动按它算 `edits[].from`。 */
    private val baselines = mutableMapOf<String, DraftCard>()

    init {
        viewModelScope.launch {
            // 阈值用户可调，界面跟着它走——写死一个数就会与设置页显示的不一致
            autoConfirmSettings.threshold.collect { value ->
                _state.update { it.copy(autoConfirmThreshold = value) }
            }
        }
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
                    // error.message 可能是「连不上 <baseUrl><path>」这类带地址的原文，
                    // 界面只认白名单文案（见 UserFacingErrors）。
                    _state.update { it.copy(isParsing = false, parseError = UserFacingErrors.userMessageFor(error)) }
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
                    fieldConfidence = outcome.fieldConfidence,
                )
                // 路线图 P0-a：高置信直接入账，低置信（或拿不到置信度）才走确认卡。
                // 置信度以 Completed 上的为准（契约字段）；拿不到就退回 fields 里的——
                // 两处都没有就是 null，照走确认，不猜。
                val confidence = outcome.confidence ?: card.confidence
                // 前提条件不满足时也退回确认卡——自动路径不该有静默失败。
                if (ConfidenceGate.canAutoPost(confidence, current.autoConfirmThreshold) &&
                    canAutoPost(card, current)
                ) {
                    autoConfirm(card, outcome.taskId, current)
                } else {
                    applyCards(listOf(card), taskId = outcome.taskId, clarification = null)
                }
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
                // problem.detail 是服务端原文（可能含上游 JSON / 路由名），不进界面。
                val message = UserFacingErrors.CAPTURE
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
        applyCards(
            cards,
            taskId = null,
            clarification = localClarification(cards, _state.value.autoConfirmThreshold),
        )
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
            pendingClarification = localClarification(
                s.cards.filterNot { it.id == id },
                s.autoConfirmThreshold,
            ),
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
            runCatching { postCards(current.cards, current) }.fold(
                onSuccess = {
                    // 修正反馈：有改动就是 edited，没动是 accepted——那是自进化最值钱的标注
                    reportFeedback(current.activeTaskId, current.fieldEdits)
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
                    // error.message 可能带着库层原文，界面只给白名单文案。
                    _state.update {
                        it.copy(isSaving = false, parseError = UserFacingErrors.SAVE)
                    }
                },
            )
        }
    }

    // ---------- 自动入账（P0-a） ----------

    /**
     * 自动入账的前提条件都满足吗。
     *
     * 缺一样就退回确认卡让用户补。**自动路径不该有失败分支**——那笔账用户没按过
     * 任何按钮，失败了不告诉他，他会以为记上了。
     */
    private fun canAutoPost(card: DraftCard, state: AddEntryUiState): Boolean {
        if (state.fromAccountId.isBlank() || state.currency.isBlank()) return false
        if (card.categoryId.isBlank()) return false
        val amount = runCatching { Money.parse(card.amountText, state.currency) }.getOrNull()
        return amount != null && !amount.isZero()
    }

    /**
     * 高置信那笔直接落库，并留下一条可撤销的提示。
     *
     * 失败时退回确认卡并说明原因：宁可让用户多按一下，也不能把钱悄悄丢了。
     */
    private suspend fun autoConfirm(card: DraftCard, taskId: String, state: AddEntryUiState) {
        runCatching { postCards(listOf(card), state) }.fold(
            onSuccess = { journalIds ->
                reportFeedback(taskId, emptyList())
                _state.update {
                    it.copy(
                        isParsing = false,
                        autoSaved = AutoSavedNotice(
                            journalId = journalIds.firstOrNull(),
                            label = autoSavedLabel(card),
                            undoAvailable = autoEntryUndo.isAvailable,
                        ),
                        cards = emptyList(),
                        pendingClarification = null,
                        activeTaskId = null,
                    )
                }
            },
            onFailure = {
                // 不拼 error.message：它可能带库层/网络原文。用户要做的只是核对后重试。
                applyCards(
                    listOf(card),
                    taskId = taskId,
                    clarification = null,
                    error = "自动入账没成功，请核对后重试",
                )
            },
        )
    }

    /** 「已入账 ¥38.50 星巴克」——金额加一句这是什么。 */
    private fun autoSavedLabel(card: DraftCard): String {
        val amount = card.amountText.ifBlank { "0" }
        val what = card.payee.ifBlank { card.note }.trim()
        return if (what.isEmpty()) "¥$amount" else "¥$amount $what"
    }

    /**
     * 撤销刚自动入账的那笔。
     *
     * 撤不掉时**如实说**：让用户以为撤了、账本里却还在，比告诉他撤不掉更糟。
     */
    fun undoAutoSaved() {
        val notice = _state.value.autoSaved ?: return
        val journalId = notice.journalId
        if (journalId == null || !notice.undoAvailable) return
        viewModelScope.launch {
            val ok = runCatching { autoEntryUndo.undo(journalId) }.getOrDefault(false)
            val (nextNotice, error) = notice.afterUndo(ok)
            _state.update {
                it.copy(
                    autoSaved = nextNotice,
                    parseError = error,
                    // 撤销成功后回到记账输入页：把刚填的原始文本也清掉，避免误以为还没提交
                    rawText = if (ok) "" else it.rawText,
                )
            }
        }
    }

    /** 用户点了「知道了」，或提示自己超时了。 */
    fun dismissAutoSaved() = _state.update { it.copy(autoSaved = null) }

    // ---------- 对话页的快速记账 ----------

    /**
     * 对话页里「确认入账」的落库入口：一句话解析出的字段直接写成凭证。
     *
     * 与确认卡走**同一条** [postCards] 路径（复式平衡由 `JournalDraft` 工厂保证），
     * 不在这里另造分录。分类按**名字**匹配科目表，对不上就用该方向的第一个——
     * 与 [matchCategoryId] 同一口径。
     *
     * [onResult] `true` 表示确实写进了账本；`false` 表示没写（账户/币种/分类没准备好，
     * 或落库失败）。调用方必须据此说人话——**一律回「记好了」是骗人**。
     */
    fun saveQuickEntry(
        amountText: String,
        categoryName: String,
        note: String,
        dateEpochDay: Long,
        onResult: (Boolean) -> Unit,
    ) {
        val current = _state.value
        if (current.fromAccountId.isBlank() || current.currency.isBlank()) {
            onResult(false)
            return
        }
        val pool = current.expenseCategories
        val categoryId = pool.firstOrNull { it.name == categoryName }?.id
            ?: pool.firstOrNull()?.id.orEmpty()
        if (categoryId.isBlank()) {
            onResult(false)
            return
        }
        val card = DraftCard(
            id = "chat-${current.fromAccountId}-${dateEpochDay}-${amountText.hashCode()}",
            kind = EntryKind.EXPENSE,
            amountText = amountText,
            categoryId = categoryId,
            payee = "",
            note = note,
            dateEpochDay = dateEpochDay,
            confidence = null,
        )
        viewModelScope.launch {
            val ok = runCatching {
                postCards(listOf(card), current, source = JournalSource.VOICE)
            }.isSuccess
            onResult(ok)
        }
    }

    /**
     * 把若干张卡片落库，返回凭证 id。**确认入账与自动入账共用这一段**。
     *
     * [source] 默认取 UI 状态里的渠道；对话页的快记是**文字**输入，走 [JournalSource.VOICE]
     * （与 [parseNow] 的文字路径同一口径），别记成 RECEIPT——「这笔是拍来的还是说来的」
     * 决定去重时谁更可信。
     */
    private suspend fun postCards(
        cards: List<DraftCard>,
        state: AddEntryUiState,
        source: JournalSource = state.aiSource,
    ): List<String> {
        val accountId = state.fromAccountId
        val currency = state.currency.ifBlank { "CNY" }
        return cards.map { card ->
            val amount = Money.parse(card.amountText, currency)
            if (amount.isZero()) error("金额不能为零")
            val categoryPool =
                if (card.kind == EntryKind.INCOME) state.incomeCategories else state.expenseCategories
            val categoryId = card.categoryId.ifBlank {
                categoryPool.firstOrNull()?.id ?: error("缺少分类")
            }
            val itemDrafts = card.items.toItemDrafts(currency)
            val draft = when (card.kind) {
                EntryKind.INCOME -> JournalDraft.income(
                    dateEpochDay = card.dateEpochDay,
                    amount = amount,
                    toAccountId = accountId,
                    categoryAccountId = categoryId,
                    payee = card.payee.ifBlank { null },
                    note = card.note.ifBlank { null },
                    source = source,
                )

                else -> JournalDraft.expense(
                    dateEpochDay = card.dateEpochDay,
                    amount = amount,
                    fromAccountId = accountId,
                    categoryAccountId = categoryId,
                    payee = card.payee.ifBlank { null },
                    note = card.note.ifBlank { null },
                    source = source,
                )
            }.copy(
                place = card.place.trim().ifBlank { null },
                items = itemDrafts,
            )
            repository.post(draft)
        }
    }

    /**
     * 回报人工校对信号。有改动就是 `edited`，没动是 `accepted`——
     * 那是自进化最值钱的标注。回报失败不影响入账，所以只吞不抛。
     */
    private suspend fun reportFeedback(taskId: String?, edits: List<FieldEdit>) {
        runCatching {
            clarificationPort.feedback(
                taskId,
                TaskFeedback(
                    verdict = if (edits.isEmpty()) FeedbackVerdict.ACCEPTED else FeedbackVerdict.EDITED,
                    edits = edits,
                ),
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
