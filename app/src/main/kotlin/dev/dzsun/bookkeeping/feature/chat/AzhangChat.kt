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
 * 三条口径：
 *  1. **查账不交给模型**：输入明显是「花了多少 / 哪类最多」时上层走 SQL 链路，
 *     这里只在模型回「查账」时把它透出去，不让模型算数。
 *  2. **模型不可用必须能降级**：一律返回 [AzhangTurn.Unavailable]，
 *     界面退回原来的规则判断与话术，聊天页不至于哑掉。
 *  3. **超时要短**：聊天是即时场景，等 90 秒等于不可用。
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
        return interpret(out)
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
        emit(AzhangStream.Finish(interpret(raw.toString())))
    }

    /** 把模型输出翻成一轮回复：数组 = 记账，`{"reply":...}` = 说话，其它 = 降级。 */
    private fun interpret(out: String): AzhangTurn {
        // 1) 用户其实在报账 → 模型直接给了数组
        val entries = runCatching { parser.decodeEntries(out) }.getOrNull()
        if (!entries.isNullOrEmpty()) return AzhangTurn.Entry(entries)

        // 2) 正常对话
        val reply = extractReply(out)
        if (reply != null) {
            val trimmed = reply.trim()
            return AzhangTurn.Reply(
                body = if (trimmed == QUERY_KEYWORD) QUERY_FALLBACK_TEXT else trimmed,
                isLedgerQuery = trimmed == QUERY_KEYWORD,
            )
        }
        Log.w(TAG, "无法解析模型输出: ${out.take(200)}")
        // 原文只露前 80 字 —— 够定位问题，又不至于把一整段乱码糊在界面上
        return AzhangTurn.Unavailable("模型输出不是合法 JSON：${out.trim().take(80)}")
    }

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
