package dev.dzsun.bookkeeping.feature.chat

import dev.dzsun.bookkeeping.llm.ModelIssue
import dev.dzsun.bookkeeping.llm.LlmChunk
import dev.dzsun.bookkeeping.llm.ReadyResult
import dev.dzsun.bookkeeping.llm.ReplyStreamFilter
import dev.dzsun.bookkeeping.llm.SourceTag
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.ThinkingUiState
import dev.dzsun.bookkeeping.llm.issueMessage
import dev.dzsun.bookkeeping.feature.entry.AmountFidelity
import dev.dzsun.bookkeeping.feature.entry.EntryDecode
import dev.dzsun.bookkeeping.feature.entry.LocalAiParser
import dev.dzsun.bookkeeping.feature.entry.SparkAiParser
import javax.inject.Inject
import javax.inject.Singleton
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import android.util.Log

/** 流式一轮对话的过程事件。 */
sealed interface AzhangStream {
    /** 正在准备模型（首次进入要加载 1GB 模型，先给用户一个状态）。 */
    data object Preparing : AzhangStream

    /**
     * 一段**思考素材**（含被 ThinkingStripper 剔掉的碎碎念，以及还没解析的正文）。
     *
     * 它走的是一条**独立的通道**：调用方把它喂进 `ThinkingUiState`，
     * 那个折叠区自己去重组。正文气泡、输入框、chips 都不参与——
     * 这是「整页不许随生成节奏抖动」那条验收的实现方式。
     */
    data class Thinking(val delta: String) : AzhangStream

    /** 可以立即显示的正文增量。 */
    data class Text(val delta: String) : AzhangStream

    /** 收尾：最终判定（出卡片 / 完整回复 / 降级）。 */
    data class Finish(val turn: AzhangTurn) : AzhangStream
}

/** 阿账一轮回复的三种结果。 */
sealed interface AzhangTurn {
    /**
     * 模型判定「这其实在报一笔账」→ 界面出记账卡片。
     * 类型用的是记一笔那套领域模型（`feature.entry.ParsedEntry`），
     * 这里用全限定名是为了避开聊天页里同名的展示用 `ParsedEntry`。
     */
    data class Entry(
        val entries: List<dev.dzsun.bookkeeping.feature.entry.ParsedEntry>,
        val source: SourceTag = SourceTag.Model,
    ) : AzhangTurn

    /**
     * 模型的自然语言回答。
     * [isLedgerQuery] 为 true 表示模型自己不答数字、让上层去查本地账本 ——
     * **计算交给 SQL，模型只翻译意图**，这是不让它编金额的关键。
     *
     * [entries] 是**保底补出来的记账卡片**（见 [withRulesEntryFallback]）：
     * 模型明明收到一句报账却只回了一句话时，界面要**既显示这句话、又出卡**，
     * 否则用户这句话就白说了。来源恒是 [SourceTag.rules]（带原因），非空时才有效。
     */
    data class Reply(
        val body: String,
        val isLedgerQuery: Boolean = false,
        val entries: List<dev.dzsun.bookkeeping.feature.entry.ParsedEntry> = emptyList(),
        /** [entries] 的来源；[entries] 为空时无意义。 */
        val entriesSource: SourceTag? = null,
        /** 这句话本身的来源，界面按它渲染气泡脚注（`src=MODEL` / `src=RULES`）。 */
        val source: SourceTag = SourceTag.Model,
    ) : AzhangTurn

    /**
     * 模型不可用（没模型 / 引擎状态卡死 / 超时 / 输出不合法）→ 调用方退回原来的规则与问答链路。
     *
     * [reason] 是**给用户看的**：说清是什么 + 下一步做什么（见 `issueMessage`），
     * 界面要原样透出，不要再另编一句与真实原因不符的文案。
     *
     * [code] 是机械判据的代码（如 `MODEL_AMOUNT_MISMATCH`），界面拿它拼 [SourceTag]，
     * 于是气泡与卡片上的来源标注口径完全一致（`src=RULES，<code>`）。
     */
    data class Unavailable(val reason: String = "", val code: String? = null) : AzhangTurn
}

/**
 * 「和阿账聊聊」的本地模型大脑。
 *
 * 与记一笔解析**共用同一个系统提示**（[SparkSession.systemPrompt]），
 * 靠用户消息前缀 [SparkSession.MODE_CHAT] 区分模式 —— 换提示词要重新加载模型，
 * 模拟器实测 65 秒，聊天里绝对不能发生。
 *
 * 四条口径：
 *  1. **查账不交给模型**：输入明显是「花了多少 / 哪类最多」时上层走 SQL 链路，
 *     这里只在模型回「查账」时把它透出去，不让模型算数。
 *  2. **模型不可用必须能降级**：一律返回 [AzhangTurn.Unavailable]，
 *     界面退回原来的规则判断与话术，聊天页不至于哑掉。
 *  3. **超时预算不在这里定**：初始化与生成的预算统一在 [SparkSession.INIT_BUDGET_MS] /
 *     [SparkSession.GEN_BUDGET_MS]，面板、对话页、问账页三处共用同一份。
 *  4. **非 JSON 输出先纠错再降级**：补救提示重试 1 次 → 仍是纯文本就按原话展示
 *     （数字护栏拦下的除外），见 [repairChatOutput]。用户聊天宁可看到模型原话，
 *     也不要看到「这次没答上」。
 *  5. **模型没给数组也要让账记得上**：回了话就再用本地规则解析兜一次底，
 *     见 [withRulesEntryFallback]。
 */
@Singleton
class AzhangChat @Inject constructor(
    private val session: SparkSession,
    private val parser: SparkAiParser,
) {

    /**
     * 离线规则解析器 —— 只在**模型回了话却没给数组**时兜一次底（见 [withRulesEntryFallback]）。
     * 复用记一笔那条路已经在用的本地解析器，不另造关键词表。
     */
    private val rulesParser = LocalAiParser()

    /**
     * **记账解析链路**（`MODE_PARSE`）—— 对话页/悬浮面板里含金额的句子优先走这条。
     *
     * 为什么不走 [streamTurn] 的 `MODE_CHAT`：那条路模型多半只回一句话
     * （实测含金额的报账 6/6 次不给数组），卡片要靠规则补，金额也只能取原文那一份。
     * 而 `MODE_PARSE` 实测 5/5 全对，且**同一份** [SparkAiParser.parseWithSource] 里
     * 已经串好了金额保真（`MODEL_AMOUNT_MISMATCH`）与规则兜底。
     *
     * 两个模式共用同一个系统提示，只在用户消息前缀上分叉 —— **不会触发重新加载模型**。
     */
    suspend fun parseEntry(text: String): dev.dzsun.bookkeeping.feature.entry.ParseOutcome =
        parser.parseWithSource(text)

    /**
     * 预热：进 AI 页面就把模型加载好，别让用户第一条消息先等几十秒。
     * 失败也无所谓 —— 真正调用时还会再试一次，然后走降级。
     */
    suspend fun warmUp(): ReadyResult = session.ensureReady()

    /**
     * 模型此刻是否已就绪。界面用它把「还在加载」和「真出不来」分开说 ——
     * 超时的时候如果它还是 false，说明卡在初始化（首次要 1~2 分钟），不是生成失败。
     */
    val isReady: Boolean get() = session.isReady

    /**
     * 引擎的真实状态 —— 等待可视化用它把「一次等待」切成
     * 加载权重 / 解码系统提示 / 生成各阶段（推导统一走 `phaseOf`，界面不自己猜）。
     */
    val engineState: StateFlow<InferenceEngine.State> get() = session.state

    suspend fun turn(
        text: String,
        initTimeoutMs: Long = INIT_TIMEOUT_MS,
        genTimeoutMs: Long = GEN_TIMEOUT_MS,
    ): AzhangTurn {
        if (text.isBlank()) return AzhangTurn.Unavailable("这句话是空的")

        val ready = withTimeoutOrNull(initTimeoutMs) { session.ensureReady() }
        val notReadyReason = when (ready) {
            // 说清「还在加载」而不是「失败」：首次冷启动系统提示解码实测 97~100s
            null -> "模型还在准备就没等到（首次约 1~2 分钟，上限 ${initTimeoutMs / 1000} 秒），先按本地规则回答"
            is ReadyResult.Failed -> issueMessage(ready.issue)
            is ReadyResult.Ready -> null
        }
        if (notReadyReason != null) {
            Log.i(TAG, "模型未就绪，聊天走降级：$notReadyReason")
            return AzhangTurn.Unavailable(notReadyReason)
        }

        var genError: Throwable? = null
        val out = withTimeoutOrNull(genTimeoutMs) {
            try {
                session.complete(SparkSession.MODE_CHAT + text, SparkSession.DEFAULT_MAX_TOKENS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                genError = e
                null
            }
        }
        if (out.isNullOrBlank()) {
            val reason = when {
                genError != null ->
                    "生成失败：${genError!!.message ?: genError!!.javaClass.simpleName}"
                out == null -> "生成超时（超过 ${genTimeoutMs / 1000} 秒没出结果）"
                else -> "模型这次没有输出任何内容"
            }
            Log.i(TAG, "$reason，聊天走降级")
            return AzhangTurn.Unavailable(reason)
        }
        return interpretRepaired(text, out, genTimeoutMs)
    }

    /**
     * 流式一轮对话：**边生成边吐可显示的正文**，悬浮面板实时上屏就靠它。
     *
     * 顺序：`Preparing`（首次要加载 1GB 模型，先告诉用户在准备）→ 若干 `Thinking`
     * （思考素材，喂折叠区）+ `Text`（可显示的正文增量，进气泡）→
     * `Finish`（最终是记账卡片、完整回复、还是降级）。
     *
     * **`Thinking` 与 `Text` 是两条通道**：前者只喂 `ThinkingUiState`，由折叠区
     * 自己去重组；后者才改气泡。混成一条的话，模型每吐一个字整页都要重排。
     *
     * **不在这里加超时**：Flow 内部再套 `withTimeoutOrNull` 会触发
     * 「从另一个协程发射」的不变量异常。超时与取消由调用方
     * `withTimeoutOrNull(budget) { azhang.streamTurn(t).collect { … } }` 统一控制。
     * 
     * 内部兜底：collect 循环中每收到一个 chunk 检查一次总耗时，防止调用方忘记设超时。
     */
    fun streamTurn(text: String): Flow<AzhangStream> = flow {
        if (text.isBlank()) {
            emit(AzhangStream.Finish(AzhangTurn.Unavailable("这句话是空的")))
            return@flow
        }
        emit(AzhangStream.Preparing)
        val ready = try {
            session.ensureReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ReadyResult.Failed(ModelIssue.LoadFailed(e.message ?: e.javaClass.simpleName))
        }
        if (ready is ReadyResult.Failed) {
            val reason = issueMessage(ready.issue)
            Log.i(TAG, "模型未就绪，流式聊天走降级：$reason")
            emit(AzhangStream.Finish(AzhangTurn.Unavailable(reason)))
            return@flow
        }

        val filter = ReplyStreamFilter()
        val raw = StringBuilder()
        val startTime = android.os.SystemClock.elapsedRealtime()
        val maxDurationMs = GEN_TIMEOUT_MS
        try {
            session.streamChunks(SparkSession.MODE_CHAT + text, SparkSession.DEFAULT_MAX_TOKENS)
                .collect { chunk ->
                    // 内部兜底：防止调用方忘记设超时，模型无限挂起
                    if (android.os.SystemClock.elapsedRealtime() - startTime > maxDurationMs) {
                        throw java.util.concurrent.TimeoutException("流式生成超过 ${maxDurationMs}ms")
                    }
                    // 思考素材走独立通道：含被 ThinkingStripper 剔掉的碎碎念，
                    // 也含还没解析的正文（用户展开时才看，默认收起）。
                    emit(AzhangStream.Thinking(chunk.text))
                    if (chunk !is LlmChunk.Answer) return@collect
                    raw.append(chunk.text)
                    val delta = filter.feed(chunk.text)
                    if (delta.isNotEmpty()) emit(AzhangStream.Text(delta))
                }
            filter.flush().takeIf { it.isNotEmpty() }?.let { emit(AzhangStream.Text(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "流式生成失败: ${e.message}")
            emit(
                AzhangStream.Finish(
                    AzhangTurn.Unavailable("生成失败：${e.message ?: e.javaClass.simpleName}"),
                ),
            )
            return@flow
        }
        emit(AzhangStream.Finish(interpretRepaired(text, raw.toString(), GEN_TIMEOUT_MS)))
    }

    /**
     * 解析一轮对话输出：数组 = 记账，回复对象 = 说话（同义字段容错，见
     * [displayableChatReply]），**解读不了返回 null**——null 交给 [repairChatOutput]
     * 补救重试，不再直接降级（1.7B 模型会吐「（暂无具体对话内容）」这种非 JSON
     * 的原话，见 [repairChatOutput]）。
     *
     * [raw] 是用户原话，**必须传**：数组是不是可信要靠它做金额保真校验
     * （见 [SparkAiParser.decodeChecked]）。少了这一步，模型报的错金额会被静默收下。
     *
     * **这个函数保持非挂起**：它作为 `parse` 回调喂给 [repairChatOutput]（纯函数、
     * 单测直接钉）。金额对不上时它只产出一个带 [SourceTag.CODE_AMOUNT_MISMATCH]
     * 的 [AzhangTurn.Unavailable] 信号，需要挂起调用的规则兜底由
     * [interpretRepaired] 在 suspend 链路里接手（见 [amountMismatchFallback]）。
     */
    private fun interpret(raw: String, out: String): AzhangTurn? {
        // 1) 用户其实在报账 → 模型直接给了数组（**带金额保真校验**）
        when (val decoded = runCatching { parser.decodeChecked(raw, out) }.getOrNull()) {
            is EntryDecode.Ok -> if (decoded.entries.isNotEmpty()) {
                return AzhangTurn.Entry(decoded.entries)
            }
            // 模型报的金额和原话对不上 → 整批作废，就地打出不匹配信号，不再补救重试
            is EntryDecode.AmountMismatch -> return AzhangTurn.Unavailable(
                AmountFidelity.describe(decoded.parsed, decoded.expected),
                SourceTag.CODE_AMOUNT_MISMATCH,
            )
            EntryDecode.NoArray, null -> Unit
        }

        // 2) 正常对话：同义字段容错 + 数字护栏（护栏不因为容错而放松）
        return displayableChatReply(out)?.let { chatReply(it) }
    }

    /**
     * 模型报的金额与用户原话对不上（真机实测：「午餐20元」被报成 30）。
     *
     * **整批作废**，不是只改那一笔：一个数字都报不准的批次，其余字段同样不可信。
     * 然后拿**用户原话**走确定性规则，规则也认不出才如实说这次没答上。
     *
     * 为什么不再补救重试一次：规则是确定性的、不花钱也不花时间，而重试要再等一次
     * 生成，还可能拿到**第二个**错数字。这一条只挡错金额，不指望模型第二次就对了。
     *
     * 规则解析是挂起调用（[LocalAiParser.parse] 在 [AiParser] 接口上就是 suspend），
     * 所以这一层是 suspend，只能从 [interpretRepaired] 的挂起链路进来。
     */
    private suspend fun amountMismatchFallback(raw: String, reason: String): AzhangTurn {
        Log.w(TAG, "金额与原文对不上，模型结果作废（$raw）：$reason")
        val rules = rulesEntries(raw)
        return if (rules.isEmpty()) {
            AzhangTurn.Unavailable(
                "$reason\n这句也没能按规则认出账目，把金额再说一遍试试。",
                SourceTag.CODE_AMOUNT_MISMATCH,
            )
        } else {
            AzhangTurn.Entry(rules, SourceTag.rules(reason, SourceTag.CODE_AMOUNT_MISMATCH))
        }
    }

    /** 规则兜底出卡：金额必须 > 0 且分类落得进科目表，否则宁可不出卡。 */
    private suspend fun rulesEntries(raw: String): List<dev.dzsun.bookkeeping.feature.entry.ParsedEntry> =
        runCatching { rulesParser.parse(raw) }.getOrNull().orEmpty()
            .filter { it.amountText.toBigDecimalOrNull()?.signum() == 1 && isKnownCategory(it) }

    /**
     * 解析 + **输出纠错**：解读不了就用补救提示重发一次（只重试 1 次），
     * 仍失败且模型吐的是一句纯文本时按 [AzhangTurn.Reply] 展示，
     * 被数字护栏拦下的除外（原因原样透给界面，不许只写日志）。
     *
     * 只服务 [SparkSession.MODE_CHAT]。记账解析（[SparkSession.MODE_PARSE]）
     * 不经过这里，照旧回落 `LocalAiParser`。
     *
     * 金额不匹配的信号在这里转成真正的规则兜底：[repairChatOutput] 是纯函数
     * （`parse` 回调必须非挂起），所以「拿用户原话走一次挂起的规则解析」
     * 只能在这一层做（见 [amountMismatchFallback]）。
     */
    private suspend fun interpretRepaired(
        text: String,
        out: String,
        remedyTimeoutMs: Long,
    ): AzhangTurn {
        val repaired = repairChatOutput(
            text = text,
            firstOut = out,
            parse = { interpret(text, it) },
            remedy = {
                withTimeoutOrNull(remedyTimeoutMs) {
                    session.complete(remedyPrompt(text), REMEDY_MAX_TOKENS)
                }
            },
            log = { level, message ->
                if (level == LogLevel.W) Log.w(TAG, message) else Log.i(TAG, message)
            },
        )
        // interpret 对「金额与原文对不上」只打信号；规则兜底是挂起调用，在这里接手
        val turn = if (repaired is AzhangTurn.Unavailable &&
            repaired.code == SourceTag.CODE_AMOUNT_MISMATCH
        ) {
            amountMismatchFallback(text, repaired.reason)
        } else {
            repaired
        }
        return withRulesEntryFallback(
            text = text,
            turn = turn,
            parse = { rulesParser.parse(it) },
            isKnownCategory = ::isKnownCategory,
        )
    }

    /** 分类必须落得进科目表，否则用户点确认时按名字找不到账户。 */
    private fun isKnownCategory(entry: dev.dzsun.bookkeeping.feature.entry.ParsedEntry): Boolean =
        if (entry.kind == dev.dzsun.bookkeeping.feature.entry.EntryKind.INCOME) {
            entry.categoryName in session.incomeCategories
        } else {
            entry.categoryName in session.expenseCategories
        }

    companion object {
        private const val TAG = "AzhangChat"

        /**
         * 首次加载（含解码系统提示）的上限 —— **只引用，不定义**。
         * 预算的唯一定义处在 [SparkSession.INIT_BUDGET_MS]，三处入口共用一份。
         */
        const val INIT_TIMEOUT_MS = SparkSession.INIT_BUDGET_MS

        /** 单轮聊天生成上限 —— 同上，唯一定义处在 [SparkSession.GEN_BUDGET_MS]。 */
        const val GEN_TIMEOUT_MS = SparkSession.GEN_BUDGET_MS

        /** 模型用这两个字表示「这个问题得查账本」。 */
        const val QUERY_KEYWORD = "查账"

        /**
         * 收到「查账」信号时的兜底文案 —— 真正的查账链路（AskViewModel → 本地 SQL）
         * 由聊天页在分类阶段就接管了，走到这里说明分类器没认出来，
         * 所以给一句**能直接照做**的引导，而不是让模型编个数字。
         */
        const val QUERY_FALLBACK_TEXT =
            "这个得看你的真实账本，我不编数字。\n试着问：「这个月餐饮花了多少」「哪类花得最多」。"
    }
}

// ============================================================================
// 对话输出的纠错：以下都是纯函数，不碰引擎、不读 Android —— 单测直接钉这些。
// ============================================================================

/** 纠错过程的日志级别：生产接 `android.util.Log`，单测接采集器（日志要能被断言）。 */
enum class LogLevel { W, I }

/** 补救重试的次数上限：**只重试 1 次**，聊天是即时场景，不能让用户等第二轮。 */
const val MAX_REMEDY_ATTEMPTS = 1

/** 补救重试的 token 上限：只求一句合法 JSON，别让它再写长篇。 */
const val REMEDY_MAX_TOKENS = 96

/** 纯文本兜底的长度上限：超过就当复述/碎碎念，不上屏（1.7B 模型有这毛病）。 */
const val MAX_PLAIN_REPLY_CHARS = 160

/**
 * 金额与百分比的形态 —— 数字护栏的判据（见 [displayablePlainText]）。
 *
 * 「元」必须**紧跟数字或中文数字**才算金额：裸 `元` 会把「元旦快乐」「欧元」
 * 这类无辜句子一起拦掉，用户就又变回看到「没答上」——护栏是拦编造的数字，
 * 不是拦这个字。
 */
private val NUMBER_GUARD = Regex("¥|￥|(?:\\d|[零一二两三四五六七八九十百千]+)元|%|\\d+\\.\\d{2}")

/**
 * 解析失败时重发的**补救提示**：把原话再讲一遍，后面压一句更硬的输出约束。
 * 比系统提示里的措辞更狠，是因为 1.7B 模型第一次没守格式，第二次要单点施压。
 */
fun remedyPrompt(text: String): String =
    SparkSession.MODE_CHAT + text + "\n只输出 JSON，不要任何其他文字。"

/**
 * 重试后仍不是 JSON 时，模型吐的这句纯文本能不能直接当回复展示。
 *
 * 用户聊天宁可看到模型的原话，也不要看到「这次没答上」——但三类输出必须拦下：
 *  1. **金额/百分比形态**（`¥38`、`28元`、`44%`、`3.50`）：编出来的数字比「没答上」伤得多，
 *     查账那条链路本来走本地 SQL；
 *  2. **JSON 残片**（含 `{}[]`）：半截代码不是一句话，展示出去等于把乱码糊用户脸上；
 *  3. **超长输出**（> [MAX_PLAIN_REPLY_CHARS]）：模型复述系统提示、自言自语的整段碎碎念。
 *
 * 返回可直接展示的正文；不可展示返回 null，调用方如实报 [AzhangTurn.Unavailable]。
 */
fun displayablePlainText(out: String): String? {
    val s = out.trim()
    if (s.isEmpty() || s.length > MAX_PLAIN_REPLY_CHARS) return null
    if (s.any { it == '{' || it == '}' || it == '[' || it == ']' }) return null
    if (NUMBER_GUARD.containsMatchIn(s)) return null
    return s
}

/** 回复正文的同义字段，按优先级取**首个非空字符串**（软测 TC-22：补救重试吐 `{"content":…}` 缺 `reply`）。 */
private val REPLY_KEYS = listOf("reply", "content", "text", "message")

/** 外层包装键：顶层没有回复字段时往里看**一层**（data / result 各一层，不递归）。 */
private val WRAPPER_KEYS = listOf("data", "result")

/** 解析回复用的 JSON（宽松：允许前后有杂音，只要外层是对象）。 */
private val replyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 从模型输出取一段**可展示**的回复正文：同义字段容错 + [displayablePlainText] 护栏。
 *
 * 容错（schema 漂移，TC-22 实测）：
 *  1. 顶层对象按 reply → content → text → message 取首个非空字符串；
 *  2. 都没有再进 data / result 各一层，里面按同样顺序找（**不递归**，一层到头）；
 *  3. 非字符串（null、数字）与空白串都跳过，不当正文。
 *
 * **护栏不放松**：取出的正文照样过 [displayablePlainText] ——
 * 金额/百分比形态、JSON 残片、超长一律拦下返回 null（交给补救重试）。
 * 容错的是字段名，不是内容尺度。数组仍由 [AzhangChat.interpret] 的记账解析先接走，不经过这里。
 */
internal fun displayableChatReply(out: String): String? =
    extractReply(out)?.let { displayablePlainText(it) }

/** 见 [displayableChatReply] 的口径；返回**未经护栏**的原始正文，护栏由调用方套。 */
private fun extractReply(out: String): String? {
    val s = out.indexOf('{')
    val e = out.lastIndexOf('}')
    if (s !in 0 until e) return null
    val obj = runCatching {
        replyJson.parseToJsonElement(out.substring(s, e + 1))
    }.getOrNull() as? JsonObject ?: return null
    return firstReplyString(obj)
        ?: WRAPPER_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonObject)?.let { firstReplyString(it) }
        }
}

/** 按 [REPLY_KEYS] 的顺序取首个非空字符串；`{"reply":null}` / `{"reply":"  "}` 都跳过。 */
private fun firstReplyString(obj: JsonObject): String? =
    REPLY_KEYS.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

/** 一句正文 → 一轮回复；「查账」仍由上层走 SQL，不让模型算数。 */
private fun chatReply(trimmed: String): AzhangTurn.Reply = AzhangTurn.Reply(
    body = if (trimmed == AzhangChat.QUERY_KEYWORD) AzhangChat.QUERY_FALLBACK_TEXT else trimmed,
    isLedgerQuery = trimmed == AzhangChat.QUERY_KEYWORD,
)

/**
 * 对话保底出卡：模型**回了话（含「查账」信号）却没给数组**时，拿用户原话再走一次
 * 已有的离线规则解析（[LocalAiParser]），解析得出条目就把卡片挂到回复上，
 * 界面因此**既显示模型那句话、又出记账确认卡**。
 *
 * 为什么必须有这一层：实测对话模式下含金额的报账 6/6 次都没输出数组
 * （同一批句子走记账解析模式 5/5 全对）——分流靠提示词是概率性的，
 * 而「账记不上」没有第二次机会，所以再补一条确定性的规则兜底。
 *
 * 三条口径：
 *  1. **模型已经给了数组就不碰规则**（[AzhangTurn.Entry] 原样返回，[parse] 一次都不调）；
 *  2. 金额必须 > 0 且**分类能落进科目表**（[isKnownCategory]），否则不出卡；
 *  3. 卡片的来源标成规则兜底（[SourceTag.rules]，**带原因与代码**），界面上要标出来——
 *     别让用户以为这些字段是模型认出来的。
 *
 * 解析器与科目表判定都注入，所以单测不需要 Android、不需要引擎。
 */
internal suspend fun withRulesEntryFallback(
    text: String,
    turn: AzhangTurn,
    parse: suspend (String) -> List<dev.dzsun.bookkeeping.feature.entry.ParsedEntry>,
    isKnownCategory: (dev.dzsun.bookkeeping.feature.entry.ParsedEntry) -> Boolean,
): AzhangTurn {
    if (turn !is AzhangTurn.Reply) return turn
    val entries = parse(text).filter { entry ->
        entry.amountText.toBigDecimalOrNull()?.signum() == 1 && isKnownCategory(entry)
    }
    if (entries.isEmpty()) return turn
    return turn.copy(
        entries = entries,
        entriesSource = SourceTag.rules(NO_ENTRY_REASON, SourceTag.CODE_NO_ENTRIES),
    )
}

/**
 * 模型回了话却没给记账条目时，卡片上写的兜底原因。
 *
 * 这句话会**原样出现在用户眼前**（卡片脚注），所以要说清发生了什么、
 * 而不是写「规则解析」四个字让用户自己猜。
 */
const val NO_ENTRY_REASON = "模型这次只回了一句话，没给出记账条目"

/**
 * 对话输出的解析与纠错：[parse] 解读不了就用 [remedy] 补救重试，
 * 次数上限 [maxRemedyAttempts]（默认 [MAX_REMEDY_ATTEMPTS] = 1）；
 * 仍失败且模型吐的是**一句纯文本**时按 [AzhangTurn.Reply] 展示，
 * 被 [displayablePlainText] 的护栏拦下时才降级 ——
 * 降级必须带上原因（含重试了几次），**不许只写日志**。
 *
 * 解析、生成、日志全部注入，所以单测不需要 Android 与引擎。
 * 只服务 [SparkSession.MODE_CHAT]；记账解析（[SparkSession.MODE_PARSE]）
 * 不经过这里，照旧回落本地规则。
 */
suspend fun repairChatOutput(
    text: String,
    firstOut: String,
    parse: (String) -> AzhangTurn?,
    remedy: suspend (attempt: Int) -> String?,
    maxRemedyAttempts: Int = MAX_REMEDY_ATTEMPTS,
    log: (LogLevel, String) -> Unit = { _, _ -> },
): AzhangTurn {
    parse(firstOut)?.let { return it }

    var latest = firstOut
    var attempt = 0
    while (attempt < maxRemedyAttempts) {
        attempt++
        log(LogLevel.W, "对话输出不是 JSON，补救重试 $attempt/$maxRemedyAttempts：${latest.trim().take(80)}")
        val retried = try {
            remedy(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log(LogLevel.W, "补救重试 $attempt 生成失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
        if (!retried.isNullOrBlank()) {
            val parsed = parse(retried)
            if (parsed != null) {
                log(LogLevel.I, "补救重试 $attempt 成功，重发后拿到合法输出")
                return parsed
            }
            latest = retried
        }
    }

    // 仍不是 JSON：对话模式宁可给用户看模型的原话，也不给一句「没答上」。
    // 只看 [latest]（重试拿到的就用重试的）——首轮那句已被模型自己推翻，拿来补位是误导。
    val plain = displayablePlainText(latest)
    if (plain != null) {
        log(LogLevel.I, "补救后仍非 JSON，按纯文本回复展示：${plain.take(80)}")
        return chatReply(plain)
    }

    val retried = if (attempt > 0) "（已补救重试 $attempt 次）" else ""
    // 原文只露前 80 字 —— 够定位问题，又不至于把一整段乱码糊在界面上
    val reason = "模型输出不是合法 JSON$retried：${latest.trim().take(80)}"
    log(LogLevel.I, "补救后仍不可解析，走降级：$reason")
    return AzhangTurn.Unavailable(reason)
}
