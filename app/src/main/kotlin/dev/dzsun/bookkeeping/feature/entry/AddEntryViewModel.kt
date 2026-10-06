package dev.dzsun.bookkeeping.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.money.Money
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
    // —— 手动模式 ——
    val kind: EntryKind = EntryKind.EXPENSE,
    val amountText: String = "",
    val categoryId: String = "",
    val fromAccountId: String = "",
    val toAccountId: String = "",
    val dateEpochDay: Long = 0L,
    val payee: String = "",
    val note: String = "",
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
        get() = cards.isNotEmpty() && cards.all { it.canSave } && !isSaving
}

@HiltViewModel
class AddEntryViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val parser: AiParser,
) : ViewModel() {

    private val _state = MutableStateFlow(AddEntryUiState(dateEpochDay = clock.today().toEpochDay()))
    val state: StateFlow<AddEntryUiState> = _state.asStateFlow()

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

    fun onPhotoClick() {
        // 拍照/截图识别同理，等 AI 接口就绪后接票据 OCR。
        _state.update { it.copy(parseError = "拍照识别即将上线，先试试文字或语音输入吧") }
    }

    fun parseNow() {
        val raw = _state.value.rawText
        if (raw.isBlank()) {
            _state.update { it.copy(parseError = "先说说这笔账，比如「今天打车花了 30」") }
            return
        }
        _state.update { it.copy(isParsing = true, parseError = null, allSaved = false) }
        viewModelScope.launch {
            val parsed = runCatching { parser.parse(raw) }
                .getOrElse {
                    _state.update { it.copy(isParsing = false, parseError = it.parseError ?: "解析失败，换种说法试试") }
                    return@launch
                }
            if (parsed.isEmpty()) {
                _state.update {
                    it.copy(isParsing = false, parseError = "没听懂金额，试试「午饭 18 元」这样的说法")
                }
                return@launch
            }
            val today = clock.today().toEpochDay()
            val current = _state.value
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
            _state.update { it.copy(isParsing = false, cards = cards) }
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

    fun onCardRemove(id: String) = _state.update { s -> s.copy(cards = s.cards.filterNot { it.id == id }) }

    private fun updateCard(id: String, transform: (DraftCard) -> DraftCard) = _state.update { s ->
        s.copy(cards = s.cards.map { if (it.id == id) transform(it) else it })
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
                    val draft = when (card.kind) {
                        EntryKind.INCOME -> JournalDraft.income(
                            dateEpochDay = card.dateEpochDay,
                            amount = amount,
                            toAccountId = accountId,
                            categoryAccountId = categoryId,
                            payee = card.payee.ifBlank { null },
                            note = card.note.ifBlank { null },
                            source = JournalSource.AI_IMPORT,
                        )

                        else -> JournalDraft.expense(
                            dateEpochDay = card.dateEpochDay,
                            amount = amount,
                            fromAccountId = accountId,
                            categoryAccountId = categoryId,
                            payee = card.payee.ifBlank { null },
                            note = card.note.ifBlank { null },
                            source = JournalSource.AI_IMPORT,
                        )
                    }
                    repository.post(draft)
                }
            }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(isSaving = false, allSaved = true, cards = emptyList(), rawText = "")
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
        }

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
