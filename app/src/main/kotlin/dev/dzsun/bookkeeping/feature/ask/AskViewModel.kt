package dev.dzsun.bookkeeping.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner
import dev.dzsun.bookkeeping.core.network.LedgerQueryClient
import dev.dzsun.bookkeeping.core.network.LedgerQueryOutcome
import dev.dzsun.bookkeeping.core.platform.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 问账走到哪一步了。 */
sealed interface AskStage {
    data object Idle : AskStage

    data object Interpreting : AskStage

    data class Answered(val answer: AskAnswer) : AskStage

    /**
     * 没能给出答案，且原因**不是用户能改的**。
     *
     * 与「失败」区分开：调度层没起、契约还没有这个能力、科目表还没建好，
     * 这三件事用户一件也做不了。界面要说清「为什么答不了」，
     * 而不是弹一句「出错了」让他反复重试。
     */
    data class Unavailable(val question: String, val reason: String) : AskStage
}

data class AskUiState(
    val question: String = "",
    val stage: AskStage = AskStage.Idle,
) {
    val isBusy: Boolean get() = stage == AskStage.Interpreting

    val canAsk: Boolean get() = question.isNotBlank() && !isBusy
}

/**
 * 问账：一句自然语言 → 一次本地可执行的查询 → 一个中文答案。
 *
 * **三段分工不许互相顶替**：
 * 1. 调度层判定（[LedgerQueryClient]）——把「星巴克花了几次」翻成 [dev.dzsun.bookkeeping.core.ledger.LedgerQuery]；
 * 2. 本机算术（[LedgerQueryRunner]）——在 Room 里聚合。账目一个字节都不出设备，
 *    服务端的 `LedgerPort` 是内存实现，它本来就看不到手机上的账本；
 * 3. 说人话（[AskAnswerFormatter]）——纯措辞，不碰数字。
 *
 * 客户端一个关键词表都没有（约定 4）。翻不出来就如实说翻不出来，
 * **绝不退回本地的「猜」，也绝不拿一个空结果冒充「你没花过钱」**。
 */
@HiltViewModel
class AskViewModel @Inject constructor(
    private val client: LedgerQueryClient,
    private val runner: LedgerQueryRunner,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    fun onQuestionChange(text: String) {
        _state.update { it.copy(question = text) }
    }

    fun onAsk() {
        val question = _state.value.question.trim()
        if (question.isEmpty() || _state.value.isBusy) return
        _state.update { it.copy(stage = AskStage.Interpreting) }
        viewModelScope.launch {
            _state.update { it.copy(stage = resolve(question)) }
        }
    }

    private suspend fun resolve(question: String): AskStage =
        when (val outcome = client.interpret(question)) {
            is LedgerQueryOutcome.Interpreted -> {
                val result = runner.run(outcome.query)
                    ?: return AskStage.Unavailable(question, "科目表还没建好，算不出金额")
                AskStage.Answered(
                    AskAnswerFormatter.format(question, outcome.query, result, clock.today()),
                )
            }

            is LedgerQueryOutcome.Unavailable -> AskStage.Unavailable(question, outcome.detail)
        }
}
