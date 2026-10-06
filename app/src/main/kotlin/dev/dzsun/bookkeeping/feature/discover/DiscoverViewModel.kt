package dev.dzsun.bookkeeping.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatRole { USER, ASSISTANT }

data class ChatItem(
    val id: String,
    val role: ChatRole,
    val text: String,
    val hasImage: Boolean = false,
    val pending: Boolean = false,
)

data class DiscoverUiState(
    val items: List<ChatItem> = listOf(
        ChatItem(
            id = "hello",
            role = ChatRole.ASSISTANT,
            text = "你好，我是投资问诊助手。\n\n可以直接问，也可以丢一张截图（持仓/账单/产品页）。",
        ),
    ),
    val draft: String = "",
    val attachedPreview: ByteArray? = null,
    val isSending: Boolean = false,
) {
    val canSend: Boolean get() = (draft.isNotBlank() || attachedPreview != null) && !isSending

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = javaClass.hashCode()
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val advisor: Advisor,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    fun onDraftChange(text: String) = _state.update { it.copy(draft = text) }

    fun onImageAttached(bytes: ByteArray) {
        _state.update { it.copy(attachedPreview = bytes) }
    }

    fun clearImage() = _state.update { it.copy(attachedPreview = null) }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val text = s.draft.trim()
        val image = s.attachedPreview
        val userId = "u-${System.currentTimeMillis()}"
        val pendingId = "a-${System.currentTimeMillis()}"

        _state.update {
            it.copy(
                items = it.items + listOf(
                    ChatItem(userId, ChatRole.USER, text.ifBlank { "（图片）" }, hasImage = image != null),
                    ChatItem(pendingId, ChatRole.ASSISTANT, "正在看…", pending = true),
                ),
                draft = "",
                attachedPreview = null,
                isSending = true,
            )
        }

        viewModelScope.launch {
            val reply = runCatching {
                advisor.ask(ChatMessage(text = text, imageBytes = image))
            }.getOrElse { "暂时没答上来，稍后再试。" }
            _state.update {
                it.copy(
                    isSending = false,
                    items = it.items.map { item ->
                        if (item.id == pendingId) item.copy(text = reply, pending = false) else item
                    },
                )
            }
        }
    }
}
