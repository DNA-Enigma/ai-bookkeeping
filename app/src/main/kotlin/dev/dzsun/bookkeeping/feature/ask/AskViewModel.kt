package dev.dzsun.bookkeeping.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner
import dev.dzsun.bookkeeping.core.ledger.LocalQueryTranslator
import dev.dzsun.bookkeeping.core.network.LedgerQueryClient
import dev.dzsun.bookkeeping.core.network.LedgerQueryOutcome
import dev.dzsun.bookkeeping.core.network.UserFacingErrors
import dev.dzsun.bookkeeping.core.platform.Clock
import dev.dzsun.bookkeeping.feature.chat.AzhangChat
import dev.dzsun.bookkeeping.feature.chat.AzhangTurn
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
 * 1. 调度层判定（[LedgerQueryClient]）——把「星巴克花了几次」翻成 [LedgerQuery]；
 * 2. 本机算术（[LedgerQueryRunner]）——在 Room 里聚合。账目一个字节都不出设备，
 *    服务端的 `LedgerPort` 是内存实现，它本来就看不到手机上的账本；
 * 3. 说人话（[AskAnswerFormatter]）——纯措辞，不碰数字。
 *
 * ## 调度层翻不出来的时候
 *
 * 退到 [LocalQueryTranslator]——**这是降级，不是第二套主路径**。它的存在理由是
 * 调度层当前根本产不出查询结构（那个账目查询工具读的是服务端自己的参考数据，
 * 返回的永远是空结果），于是这条意图在契约补齐前一句话也答不出来。
 *
 * 两点必须说清：
 * - **只在前者给不出查询时启用**（`Unavailable`），调度层一旦给出结构就用它的；
 * - 答案里会标明这一句是本地规则理解的——词法翻译会把「交通银行」这类
 *   含分类名的商户认错，用户看得见来源才有可能发现，看不见就只能信。
 *
 * 两条路都翻不出来时如实说翻不出来，**绝不拿一个空结果冒充「你没花过钱」**。
 */
@HiltViewModel
class AskViewModel @Inject constructor(
    private val client: LedgerQueryClient,
    private val translator: LocalQueryTranslator,
    private val runner: LedgerQueryRunner,
    private val clock: Clock,
    private val azhang: AzhangChat,
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())

    /**
     * 问账页是 AI 悬浮球的落点，也就是用户最可能**第一个**碰到模型的地方——
     * 进来就预热，省得他打完字才开始加载模型。
     */
    init {
        viewModelScope.launch { azhang.warmUp() }
    }
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

    private suspend fun resolve(question: String): AskStage {
        val outcome = client.interpret(question)

        if (outcome is LedgerQueryOutcome.Interpreted) {
            return answer(question, outcome.query, local = false)
        }

        // 服务端给不出查询结构时退到本地词法翻译。翻得出来就照常作答，
        // 翻不出来才说「答不了」——原因用白名单文案，不透传服务端 detail。
        val local = translator.translate(question, clock.today())
            ?: return askWithModel(question)

        return answer(question, local, local = true)
    }

    /**
     * 词法翻译不出来 → 交给**本机模型**接话（问账页也是用本地 AI 的页面）。
     *
     * 口径必须守住：SQL 算不出来的问题，模型**也不许编数字**。
     * 它要么给一句不涉及金额的回答，要么自己认领「查账」/给不出 → 照实返回 [AskStage.Unavailable]，
     * 让界面说清为什么答不了，而不是拿一句像模像样的话冒充账目答案。
     */
    private suspend fun askWithModel(question: String): AskStage {
        val turn = runCatching {
            azhang.turn(question, genTimeoutMs = MODEL_GEN_TIMEOUT_MS)
        }.getOrDefault(AzhangTurn.Unavailable)

        val reply = turn as? AzhangTurn.Reply
            ?: return AskStage.Unavailable(question, UserFacingErrors.ASK)
        // 模型自己说「这得查账」，或者压根没答出来 → 如实答不了
        if (reply.isLedgerQuery || reply.body.isBlank()) {
            return AskStage.Unavailable(question, UserFacingErrors.ASK)
        }
        return AskStage.Answered(
            AskAnswer(
                question = question,
                headline = reply.body,
                detail = emptyList(),
                footnote = MODEL_NOTE,
            ),
        )
    }

    private suspend fun answer(question: String, query: LedgerQuery, local: Boolean): AskStage {
        val result = runner.run(query)
            ?: return AskStage.Unavailable(question, "科目表还没建好，算不出金额")

        val formatted = AskAnswerFormatter.format(question, query, result, clock.today())
        if (!local) return AskStage.Answered(formatted)

        // 本地规则可能认错筛选条件（「交通银行」→ 分类「交通」），
        // 把来源写在用户看得见的地方，他才有机会发现答案不是他要问的。
        return AskStage.Answered(
            formatted.copy(
                footnote = listOfNotNull(formatted.footnote, LOCAL_NOTE).joinToString("；"),
            ),
        )
    }

    private companion object {
        const val LOCAL_NOTE = "这句话是按本地规则理解的，没有经过 AI 服务"
        const val MODEL_NOTE = "这句由本机模型回答，没有查账本"

        /** 问账是即时场景：宁可答不了，也不让用户盯着转圈。 */
        const val MODEL_GEN_TIMEOUT_MS = 20_000L
    }
}
