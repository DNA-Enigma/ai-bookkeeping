package dev.dzsun.bookkeeping.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.database.JournalItemEntity
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EntryDetailUiState(
    val isLoading: Boolean = true,
    /** 没找到这笔时为 null（比如刚被删掉）。 */
    val row: LedgerRow? = null,
    /** 「买了什么」。没有明细时是空列表——不是错误。 */
    val items: List<JournalItemEntity> = emptyList(),
)

/**
 * 账目详情。明细是「几周后想起买了什么」的答案，这里只做展示——
 * **明细之和不必等于总额**（折扣、税、抹零），所以也不显示「合计是否对齐」。
 */
@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    private val repository: LedgerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val journalId: String = checkNotNull(savedStateHandle["journalId"])

    private val _uiState = MutableStateFlow(EntryDetailUiState())
    val uiState: StateFlow<EntryDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = EntryDetailUiState(
                isLoading = false,
                row = repository.observeEntry(journalId).first(),
                items = repository.itemsOf(journalId),
            )
        }
    }
}
