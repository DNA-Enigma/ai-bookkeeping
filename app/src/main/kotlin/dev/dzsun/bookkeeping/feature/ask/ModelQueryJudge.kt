package dev.dzsun.bookkeeping.feature.ask

import android.os.SystemClock
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.llm.LlmChunk
import dev.dzsun.bookkeeping.llm.ModelIssue
import dev.dzsun.bookkeeping.llm.ReadyResult
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.ThinkingUiState
import dev.dzsun.bookkeeping.llm.issueMessage
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 判定的两种结局：真判出来了，或者模型这一段没接上（带原因）。 */
sealed interface QueryJudgeOutcome {
    data class Judged(val verdict: QueryVerdict) : QueryJudgeOutcome

    /**
     * 模型没接上 / 没等到 / 抛错。[reason] 是**给用户看的**（与 `issueMessage` 同一套话术），
     * 调用方原样塞进 `SourceTag.rules`，不许另编一句与真实原因不符的文案。
     */
    data class Unavailable(val reason: String) : QueryJudgeOutcome
}

/**
 * 「这句话在问账本吗」——**交给本机模型判定**。
 *
 * ## 它替换掉了什么
 *
 * 原先问账页的判定顺序是：调度层（`LedgerQueryClient`）→ 本地词法 → 本机模型。
 * 于是最常见的问法全被前两级吃掉，模型几乎轮不到，页面脚注写着
 * 「这句话是按本地规则理解的，没有经过 AI 服务」——那等于说功能没接入。
 * 现在反过来：**模型是判定这一步的唯一主路径**，词法只在模型没接上时兜底，
 * 而且兜底必须带原因。
 *
 * ## 它不做什么
 *
 * **不算金额**。模型只被问「这是不是一句问账、条件是什么」，答案由
 * [dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner] 在本机 SQL 里聚合出来。
 * 账目一个字节都不出设备，模型也从不接触任何一笔账目。
 *
 * **不算日期**。模型给的是 `this_month` 这样的符号，换算成 epoch day 由
 * [QueryRange.span] 在本机做——1.7B 模型算日期实测必错。
 *
 * ## 为什么不做补救重试
 *
 * 对话那条路（`AzhangChat.repairChatOutput`）有 1 次补救，因为 1.7B 模型
 * 吐纯文本的概率不低。问账判定这个输出只有一个很小的对象，判据在提示词里
 * 写到了逐字段的程度，再重试一次等于让用户多等一整轮生成；
 * 而**兜底那条路（本地词法）本来就答得出最常见的问法**，
 * 代价只是脚注上多一句带原因的「模型未接上」。这个取舍是刻意的。
 */
@Singleton
class ModelQueryJudge @Inject constructor(
    private val session: SparkSession,
    private val database: LedgerDatabase,
) {

    /**
     * 判一句问话。
     *
     * @param thinking 思考区的状态。传了就**同时**把思考段与判定原文喂进去——
     *   这里的「正文」是给程序看的 JSON，不是给用户看的话，所以它进思考区
     *   （可展开查看模型到底判了什么）而不是进答案卡片。
     */
    suspend fun judge(
        question: String,
        today: LocalDate,
        thinking: ThinkingUiState? = null,
        initTimeoutMs: Long = SparkSession.INIT_BUDGET_MS,
        genTimeoutMs: Long = MODEL_GEN_TIMEOUT_MS,
    ): QueryJudgeOutcome {
        if (question.isBlank()) return QueryJudgeOutcome.Unavailable("这句话是空的")

        val ready = withTimeoutOrNull(initTimeoutMs) { session.ensureReady() }
        val notReady = when (ready) {
            // 说清「还在加载」而不是「失败」：首次冷启动解码系统提示实测 97~100s
            null -> "模型还在准备就没等到（首次约 1~2 分钟，上限 ${initTimeoutMs / 1000} 秒）"
            is ReadyResult.Failed -> issueMessage(ready.issue)
            is ReadyResult.Ready -> null
        }
        if (notReady != null) return QueryJudgeOutcome.Unavailable(notReady)

        val raw = StringBuilder()
        var error: Throwable? = null
        val finished = withTimeoutOrNull(genTimeoutMs) {
            try {
                session.streamChunks(SparkSession.MODE_QUERY + question).collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Thought -> publish(thinking, chunk.text)
                        is LlmChunk.Answer -> {
                            raw.append(chunk.text)
                            publish(thinking, chunk.text)
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
                false
            }
        }

        if (finished != true) {
            val reason = when {
                error != null -> "生成失败：${error!!.message ?: error!!.javaClass.simpleName}"
                else -> "生成超时（超过 ${genTimeoutMs / 1000} 秒没出结果）"
            }
            return QueryJudgeOutcome.Unavailable(reason)
        }

        val out = raw.toString()
        if (out.isBlank()) return QueryJudgeOutcome.Unavailable("模型这次没有输出任何内容")

        return QueryJudgeOutcome.Judged(
            parseQueryVerdict(out, today, categoryNames()),
        )
    }

    /**
     * 科目表里的分类名。读不到就给空集合——**不影响查询本身**，
     * 只是「餐饮」不再被认成分类，会退化成商户包含匹配，照样查得到。
     * 与 [dev.dzsun.bookkeeping.core.ledger.LocalQueryTranslator] 同一个口径。
     */
    private suspend fun categoryNames(): Set<String> = runCatching {
        database.accountDao()
            .findByTypes(listOf(AccountType.EXPENSE, AccountType.INCOME))
            .map { it.name }
            .toSet()
    }.getOrDefault(emptySet())

    private fun publish(thinking: ThinkingUiState?, delta: String) {
        thinking?.append(delta, SystemClock.elapsedRealtime())
    }
}
