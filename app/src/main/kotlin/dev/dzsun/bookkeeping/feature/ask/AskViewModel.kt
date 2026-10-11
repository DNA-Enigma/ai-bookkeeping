package dev.dzsun.bookkeeping.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner
import dev.dzsun.bookkeeping.core.ledger.LocalQueryTranslator
import dev.dzsun.bookkeeping.core.platform.Clock
import dev.dzsun.bookkeeping.feature.chat.AzhangChat
import dev.dzsun.bookkeeping.llm.SourceTag
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.ThinkingUiState
import com.arm.aichat.InferenceEngine
import android.os.SystemClock
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

    /**
     * 出答案了。
     *
     * [source] 是这一步判定的来源，**三处入口同一套口径**（[SourceTag]）：
     * 模型给的写 `src=MODEL`，规则兜底的写 `模型未接上（<原因>），本次按规则处理（src=RULES）`。
     * 界面在 [SourceTag.isModel] 为 false 时还要给一个「去加载模型」的入口。
     */
    data class Answered(val answer: AskAnswer, val source: SourceTag) : AskStage

    /**
     * 没能给出答案，且原因**不是用户能改的**。
     *
     * 与「失败」区分开：科目表还没建好、模型的输出连兜底都救不回来，
     * 这两件事用户一件也做不了。界面要说清「为什么答不了」，
     * 而不是弹一句「出错了」让他反复重试。
     */
    data class Unavailable(val question: String, val reason: String) : AskStage
}

data class AskUiState(
    val question: String = "",
    val stage: AskStage = AskStage.Idle,

    /**
     * 本次提问的起点（`SystemClock.elapsedRealtime()`）。等待态显示「已用时」用它，
     * null 表示还没问过（界面就不显示秒数，不编一个 0 秒出来）。
     */
    val waitStart: Long? = null,

    /**
     * 这一步是否真的落在**本机模型**上。
     *
     * 判定这一步现在**只有**本机模型一个主路径（见 [ModelQueryJudge]），
     * 所以它恒为 true；留着是因为等待态要按引擎真实状态显示阶段，
     * 而阶段必须对应真实发生的动作——假如今后加了别的判定来源，
     * 这里就是那个开关，界面一行都不用改。
     */
    val usesModel: Boolean = false,

    /**
     * 「思考过程」折叠区的状态。**它是一份独立的状态对象**，不是字符串——
     * 模型每吐一块只会让折叠区自己重组，输入框、答案卡片、推荐问题都不跟着重画。
     */
    val thinking: ThinkingUiState = ThinkingUiState(),
) {
    val isBusy: Boolean get() = stage == AskStage.Interpreting

    val canAsk: Boolean get() = question.isNotBlank() && !isBusy
}

/**
 * 问账：一句自然语言 → 一次本地可执行的查询 → 一个中文答案。
 *
 * **三段分工不许互相顶替**：
 * 1. 判定（[ModelQueryJudge]，**本机模型、离线**）——把「星巴克花了几次」翻成 [LedgerQuery]；
 * 2. 本机算术（[LedgerQueryRunner]）——在 Room 里聚合。账目一个字节都不出设备；
 * 3. 说人话（[AskAnswerFormatter]）——纯措辞，不碰数字。
 *
 * ## 判定为什么必须走本机模型
 *
 * 原先的顺序是「调度层 → 本地词法 → 模型」，于是最常见的问法在前两级就被吃掉了，
 * 用户看到的脚注是「这句话是按本地规则理解的，没有经过 AI 服务」——
 * 那等于告诉他这个功能没接入。现在**模型是唯一主路径**：
 *
 * - 模型没接上 / 没等到 / 输出不合法 → 才退到 [LocalQueryTranslator]，
 *   并且答案脚注上写清**具体原因**（[SourceTag] 在构造时就要求 RULES 必带原因）；
 * - 两条路都翻不出来 → 如实说翻不出来，**绝不拿一个空结果冒充「你没花过钱」**。
 *
 * ## 一条刻意的不作为
 *
 * 模型判「这不是一句问账」时，这里**不会**去编一个账目答案，也不会把它当成错误——
 * 就直接说「这句不是在问账本」，并把能问的例子摆出来。用户看得见判定的来源（`src=MODEL`），
 * 也就知道不是功能坏了。
 */
@HiltViewModel
class AskViewModel @Inject constructor(
    private val judge: ModelQueryJudge,
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

    /**
     * 引擎真实状态 —— 等待可视化只认它推导阶段（走 [dev.dzsun.bookkeeping.llm.phaseOf]），
     * 问账页不自己猜现在到哪一步了。
     */
    val engineState: StateFlow<InferenceEngine.State> get() = azhang.engineState

    fun onQuestionChange(text: String) {
        _state.update { it.copy(question = text) }
    }

    fun onAsk() {
        val question = _state.value.question.trim()
        if (question.isEmpty() || _state.value.isBusy) return
        val thinking = _state.value.thinking
        thinking.start(SystemClock.elapsedRealtime())
        _state.update {
            it.copy(
                stage = AskStage.Interpreting,
                waitStart = SystemClock.elapsedRealtime(),
                usesModel = true,
            )
        }
        viewModelScope.launch {
            val stage = resolve(question, thinking)
            thinking.finish(SystemClock.elapsedRealtime())
            _state.update { it.copy(stage = stage) }
        }
    }

    private suspend fun resolve(question: String, thinking: ThinkingUiState): AskStage {
        // 1) 主路径：本机模型判定
        val failure: Pair<String, String?> = when (val outcome = judge.judge(question, clock.today(), thinking)) {
            is QueryJudgeOutcome.Unavailable -> outcome.reason to null

            is QueryJudgeOutcome.Judged -> when (val verdict = outcome.verdict) {
                is QueryVerdict.Ledger ->
                    return answer(question, verdict.query, SourceTag.Model)

                // 模型说这不是问账本。**照实说**，不编一个账目答案，也不当成错误——
                // 来源照样标 src=MODEL，用户知道这是判定结果而不是功能坏了。
                QueryVerdict.Chatter -> return AskStage.Answered(
                    AskAnswer(
                        question = question,
                        headline = "这句不是在问账本",
                        detail = EXAMPLE_QUESTIONS.map { "· $it" },
                        footnote = SourceTag.Model.footnote(),
                    ),
                    SourceTag.Model,
                )

                // 输出不合法：不猜、不兜半句，落到规则兜底，原因原样上报
                is QueryVerdict.Invalid -> verdict.reason to SourceTag.CODE_BAD_OUTPUT
            }
        }
        val (reason, code) = failure

        // 2) 兜底：本地词法。翻得出来就照常作答，**但脚注上必须写清为什么没走模型**
        val local = translator.translate(question, clock.today())
            ?: return AskStage.Unavailable(
                question,
                "$reason\n\n本地规则也认不出这句问话。试试问「${EXAMPLE_QUESTIONS.first()}」。",
            )

        return answer(question, local, SourceTag.rules(reason, code))
    }

    private suspend fun answer(question: String, query: LedgerQuery, source: SourceTag): AskStage {
        val result = runner.run(query)
            ?: return AskStage.Unavailable(question, "科目表还没建好，算不出金额")

        val formatted = AskAnswerFormatter.format(question, query, result, clock.today())

        // 来源与口径都写在用户看得见的地方：规则兜底时词法可能认错筛选条件
        // （「交通银行」→ 分类「交通」），他看得见来源才有机会发现答案不是他要问的。
        return AskStage.Answered(
            formatted.copy(
                footnote = listOfNotNull(formatted.footnote, source.footnote()).joinToString("；"),
            ),
            source,
        )
    }

}

/**
 * 不知道该问什么的时候摆在页面上的几句。**是界面文案，不是关键词表**——
 * 点一下只是把字填进输入框，判定仍然全部走本机模型。
 */
/**
 * 问账判定让本机模型生成时的预算 —— **只引用，不定义**。
 *
 * 唯一定义处在 [SparkSession.GEN_BUDGET_MS]（90s）。这里原先写死 20s，
 * 比实测单轮生成中位数还短，于是英文追问之类走模型的问账必超时。
 */
internal const val MODEL_GEN_TIMEOUT_MS: Long = SparkSession.GEN_BUDGET_MS

internal val EXAMPLE_QUESTIONS = listOf(
    "这个月花了多少",
    "星巴克花了几次",
    "这个月的餐饮花了多少",
)
