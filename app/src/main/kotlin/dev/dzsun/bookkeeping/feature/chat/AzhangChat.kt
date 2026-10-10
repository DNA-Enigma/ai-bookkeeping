package dev.dzsun.bookkeeping.feature.chat

import dev.dzsun.bookkeeping.llm.ModelIssue
import dev.dzsun.bookkeeping.llm.ReadyResult
import dev.dzsun.bookkeeping.llm.ReplyStreamFilter
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.issueMessage
import dev.dzsun.bookkeeping.feature.entry.SparkAiParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log

/** 流式一轮对话的过程事件。 */
sealed interface AzhangStream {
    /** 正在准备模型（首次进入要加载 1GB 模型，先给用户一个状态）。 */
    data object Preparing : AzhangStream

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
    data class Entry(val entries: List<dev.dzsun.bookkeeping.feature.entry.ParsedEntry>) : AzhangTurn

    /**
     * 模型的自然语言回答。
     * [isLedgerQuery] 为 true 表示模型自己不答数字、让上层去查本地账本 ——
     * **计算交给 SQL，模型只翻译意图**，这是不让它编金额的关键。
     */
    data class Reply(val body: String, val isLedgerQuery: Boolean = false) : AzhangTurn

    /**
     * 模型不可用（没模型 / 引擎状态卡死 / 超时 / 输出不合法）→ 调用方退回原来的规则与问答链路。
     *
     * [reason] 是**给用户看的**：说清是什么 + 下一步做什么（见 `issueMessage`），
     * 界面要原样透出，不要再另编一句与真实原因不符的文案。
     */
    data class Unavailable(val reason: String = "") : AzhangTurn
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
 *  3. **超时要短**：聊天是即时场景，等 90 秒等于不可用。
 *  4. **非 JSON 输出先纠错再降级**：补救提示重试 1 次 → 仍是纯文本就按原话展示
 *     （数字护栏拦下的除外），见 [repairChatOutput]。用户聊天宁可看到模型原话，
 *     也不要看到「这次没答上」。
 */
@Singleton
class AzhangChat @Inject constructor(
    private val session: SparkSession,
    private val parser: SparkAiParser,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 预热：进 AI 页面就把模型加载好，别让用户第一条消息先等几十秒。
     * 失败也无所谓 —— 真正调用时还会再试一次，然后走降级。
     */
    suspend fun warmUp(): ReadyResult = session.ensureReady()

    suspend fun turn(
        text: String,
        initTimeoutMs: Long = INIT_TIMEOUT_MS,
        genTimeoutMs: Long = GEN_TIMEOUT_MS,
    ): AzhangTurn {
        if (text.isBlank()) return AzhangTurn.Unavailable("这句话是空的")

        val ready = withTimeoutOrNull(initTimeoutMs) { session.ensureReady() }
        val notReadyReason = when (ready) {
            null -> "模型准备超时（${initTimeoutMs}ms），先按本地规则回答"
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
                out == null -> "生成超时（${genTimeoutMs}ms）"
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
     * 顺序：`Preparing`（首次要加载 1GB 模型，先告诉用户在准备）→ 若干 `Text` 增量 →
     * `Finish`（最终是记账卡片、完整回复、还是降级）。
     *
     * **不在这里加超时**：Flow 内部再套 `withTimeoutOrNull` 会触发
     * 「从另一个协程发射」的不变量异常。超时与取消由调用方
     * `withTimeoutOrNull(budget) { azhang.streamTurn(t).collect { … } }` 统一控制。
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
        try {
            session.stream(SparkSession.MODE_CHAT + text, SparkSession.DEFAULT_MAX_TOKENS)
                .collect { chunk ->
                    raw.append(chunk)
                    val delta = filter.feed(chunk)
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
     * 解析一轮对话输出：数组 = 记账，`{"reply":...}` = 说话，**解读不了返回 null**——
     * null 交给 [repairChatOutput] 补救重试，不再直接降级（1.7B 模型会吐
     * 「（暂无具体对话内容）」这种非 JSON 的原话，见 [repairChatOutput]）。
     */
    private fun interpret(out: String): AzhangTurn? {
        // 1) 用户其实在报账 → 模型直接给了数组
        val entries = runCatching { parser.decodeEntries(out) }.getOrNull()
        if (!entries.isNullOrEmpty()) return AzhangTurn.Entry(entries)

        // 2) 正常对话
        return extractReply(out)?.trim()?.let { chatReply(it) }
    }

    /**
     * 解析 + **输出纠错**：解读不了就用补救提示重发一次（只重试 1 次），
     * 仍失败且模型吐的是一句纯文本时按 [AzhangTurn.Reply] 展示，
     * 被数字护栏拦下的除外（原因原样透给界面，不许只写日志）。
     *
     * 只服务 [SparkSession.MODE_CHAT]。记账解析（[SparkSession.MODE_PARSE]）
     * 不经过这里，照旧回落 `LocalAiParser`。
     */
    private suspend fun interpretRepaired(
        text: String,
        out: String,
        remedyTimeoutMs: Long,
    ): AzhangTurn = repairChatOutput(
        text = text,
        firstOut = out,
        parse = { interpret(it) },
        remedy = {
            withTimeoutOrNull(remedyTimeoutMs) {
                session.complete(remedyPrompt(text), REMEDY_MAX_TOKENS)
            }
        },
        log = { level, message ->
            if (level == LogLevel.W) Log.w(TAG, message) else Log.i(TAG, message)
        },
    )

    private fun extractReply(out: String): String? {
        val s = out.indexOf('{')
        val e = out.lastIndexOf('}')
        if (s !in 0 until e) return null
        val obj = runCatching {
            json.parseToJsonElement(out.substring(s, e + 1))
        }.getOrNull() as? JsonObject ?: return null
        return obj["reply"]?.jsonPrimitive?.content
    }

    companion object {
        private const val TAG = "AzhangChat"

        /** 首次加载（含解码系统提示）的上限；模拟器实测 65s，真机应远快于此。 */
        const val INIT_TIMEOUT_MS = 180_000L

        /** 单轮聊天生成上限：即时场景，宁可降级也不让用户干等。 */
        const val GEN_TIMEOUT_MS = 45_000L

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

/** 金额与百分比的形态 —— 数字护栏的判据（见 [displayablePlainText]）。 */
private val NUMBER_GUARD = Regex("¥|￥|元|%|\\d+\\.\\d{2}")

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

/** 一句正文 → 一轮回复；「查账」仍由上层走 SQL，不让模型算数。 */
private fun chatReply(trimmed: String): AzhangTurn.Reply = AzhangTurn.Reply(
    body = if (trimmed == AzhangChat.QUERY_KEYWORD) AzhangChat.QUERY_FALLBACK_TEXT else trimmed,
    isLedgerQuery = trimmed == AzhangChat.QUERY_KEYWORD,
)

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
