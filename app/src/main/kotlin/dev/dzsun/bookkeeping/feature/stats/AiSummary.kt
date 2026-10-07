package dev.dzsun.bookkeeping.feature.stats

import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.network.ClientInfo
import dev.dzsun.bookkeeping.core.network.Constraints
import dev.dzsun.bookkeeping.core.network.Declared
import dev.dzsun.bookkeeping.core.network.DispatcherClient
import dev.dzsun.bookkeeping.core.network.DispatcherConfig
import dev.dzsun.bookkeeping.core.network.Identity
import dev.dzsun.bookkeeping.core.network.TaskAccepted
import dev.dzsun.bookkeeping.core.network.TaskEnvelope
import dev.dzsun.bookkeeping.core.network.TaskInput
import dev.dzsun.bookkeeping.core.network.TaskSnapshot
import dev.dzsun.bookkeeping.core.network.nestedObjects
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * 交给调度层的月度数字。
 *
 * **数字在客户端算好、随 prompt 一起发出去**，所以这是一次纯文本请求：
 * 服务端不需要（也看不到）用户的账本。这正是它够格走 `direct_answer` 的原因——
 * 该路由的适用条件是「不需要任何用户私有数据即可回答」，而这里的数据是我们自己
 * 打包进 prompt 的。契约里那条「后端的 LedgerPort 看不到手机上的账本」在这里
 * 不构成障碍，反而把分工逼到了正确的位置：**算术在本地，措辞交给模型**。
 *
 * 这也意味着每次原始数字一变就是一次真实的模型开销，调用方必须去重
 * （见 [StatsViewModel] 里按 [MonthlySummaryFacts] 相等性做的判定）。
 */
data class MonthlySummaryFacts(
    /** 周期名，直接用界面上的档位文案（本月/近三月/今年）。 */
    val periodLabel: String,
    val from: LocalDate,
    val to: LocalDate,
    val currency: String,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val dailyAverageMinor: Long,
    /** SQL 已按金额倒序。 */
    val byCategory: List<CategorySlice>,
    /** 近 6 个月，用于趋势。缺月已补零。 */
    val bars: List<MonthBar>,
) {
    /** 一个字都没记时不去问模型：那是白花钱，而且问不出东西。 */
    val isEmpty: Boolean get() = expenseMinor == 0L && incomeMinor == 0L
}

/**
 * 组装给模型的 prompt。
 *
 * 两个刻意的取舍：
 * - **金额一律走 [Money.toPlainString]**，不在这里做除法或格式化——浮点数字进账本
 *   是数据损坏，而这里的数字会以文本形式被模型引用回给用户，同样不能是 `32.00000001`。
 * - **明确要求「只用上面给出的数字」**。直答的系统提示词已经写死「不要给一个看起来
 *   像那么回事的数字」，但那说的是它自己编的数；这里再补一句，是因为 prompt 里
 *   既有总额又有分类，模型很容易顺手把两项相加再报一个不存在的合计。
 */
internal fun buildSummaryPrompt(facts: MonthlySummaryFacts): String {
    val money = { minor: Long -> Money.of(minor, facts.currency).format() }
    val lines = mutableListOf<String>()

    lines += "请根据下面这份记账汇总，写一段消费小结。"
    lines += ""
    lines += "统计区间：${facts.periodLabel}（${facts.from} 至 ${facts.to}）"
    lines += "总支出：${money(facts.expenseMinor)}"
    lines += "总收入：${money(facts.incomeMinor)}"
    lines += "结余：${money(facts.incomeMinor - facts.expenseMinor)}"
    lines += "日均支出：${money(facts.dailyAverageMinor)}"

    if (facts.byCategory.isNotEmpty()) {
        lines += ""
        lines += "支出分类（按金额从多到少）："
        facts.byCategory.take(MAX_CATEGORIES).forEach { slice ->
            val pct = (slice.ratio * 100).roundToInt()
            lines += "- ${slice.name}：${money(slice.amountMinor)}（占 $pct%）"
        }
    }

    if (facts.bars.isNotEmpty()) {
        lines += ""
        lines += "近 6 个月支出趋势："
        facts.bars.forEach { bar ->
            lines += "- ${bar.yearMonth}：支出 ${money(bar.expenseMinor)}，" +
                "收入 ${money(bar.incomeMinor)}"
        }
    }

    lines += ""
    lines += "请用两三句话总结：指出占比最高的分类，并给一个具体、可执行的建议。" +
        "只使用上面给出的数字，不要自行推算或补充没有提到的数据。"

    return lines.joinToString("\n")
}

/** 直答产出的那段话与它自己的把握。 */
data class SummaryAnswer(val text: String, val confidence: Float?)

/**
 * 从任务产物里读直答的 `answer`。
 *
 * 实测 `path=direct_llm` 的产物是**平的**：`{"answer": …, "tier": …, "confidence": …}`
 * （直答没有节点，所以没有 `main` 那层壳，见 dispatcher 的 `stages/direct.py`）。
 * 但仍按判别键递归找，而不是假定它挂在顶层——`getResult` 那个端点返回的是
 * `{"task_id":…, "artifacts":{…}}`，同一份产物换个入口就多一层。
 *
 * 判别键是「有非空的字符串 `answer`」。没有它返回 null，调用方据此降级到本地模板；
 * **绝不拿整段 JSON 或一个空串冒充小结**。
 */
internal fun parseSummaryAnswer(root: JsonObject?): SummaryAnswer? {
    val node = root?.nestedObjects()?.firstOrNull { it.hasNonBlankAnswer() } ?: return null
    val text = (node[KEY_ANSWER] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    val confidence = (node[KEY_CONFIDENCE] as? JsonPrimitive)?.floatOrNull
    return SummaryAnswer(text, confidence)
}

private fun JsonObject.hasNonBlankAnswer(): Boolean =
    (this[KEY_ANSWER] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

private const val KEY_ANSWER = "answer"
private const val KEY_CONFIDENCE = "confidence"

/** 分类最多列这么多个，再多模型也只是复述，白占 token。 */
private const val MAX_CATEGORIES = 8

/**
 * 一次月度小结的结局。
 *
 * [Unavailable] 不是异常，是**常态**——实测 `direct_llm` 的档位是 cheap，
 * 而它在 dispatcher 里配了 8 秒超时（`config/routing.policy.yaml` 的
 * `direct_llm.timeout_ms`），同步路径下这个值经常不够。所以界面必须有一条
 * 能看的本地降级，而不是空白或错误框。
 */
sealed interface AiSummaryOutcome {
    data class Analyzed(
        val text: String,
        val confidence: Float?,
        val taskId: String,
    ) : AiSummaryOutcome

    /** [detail] 是给人看的原因，不参与任何判断。 */
    data class Unavailable(val detail: String) : AiSummaryOutcome
}

/**
 * 月度小结从哪来。
 *
 * 界面只认这个接口，[StatsViewModel] 因此不直接依赖 [DispatcherClient]——
 * 换实现只动 `StatsModule` 里的一个绑定。
 */
interface AiSummarySource {
    suspend fun summarize(facts: MonthlySummaryFacts): AiSummaryOutcome
}

/**
 * 走调度层直答通道生成小结。
 *
 * **用轮询而不是事件流**，与 `LedgerQueryClient` 同一个理由：直答是一次补全，
 * 没有逐节点的中间产物可看，事件流在这里能给的只有进度动画，却要多担一条长连接的
 * 断线、重放与服务端流故障。等真的需要流式输出（一个字一个字往外蹦）再换。
 */
@Singleton
class DispatcherAiSummarySource @Inject constructor(
    private val client: DispatcherClient,
    private val config: DispatcherConfig,
    private val ids: IdGenerator,
) : AiSummarySource {

    override suspend fun summarize(facts: MonthlySummaryFacts): AiSummaryOutcome {
        require(!facts.isEmpty) { "没有数据就没有可分析的东西" }
        val outcome = withTimeoutOrNull(TIMEOUT_MS) { run(facts) }
        return outcome ?: AiSummaryOutcome.Unavailable("小结生成超时")
    }

    private suspend fun run(facts: MonthlySummaryFacts): AiSummaryOutcome = try {
        val accepted = client.submitTask(
            TaskEnvelope(
                requestId = ids.newId(),
                identity = Identity(userId = config.userId),
                input = TaskInput(text = buildSummaryPrompt(facts)),
                // 直答没有工具、没有副作用，且**不需要服务端碰用户数据**——
                // 数字是我们自己塞进 prompt 的。`chat.explain` 的词表描述
                // （「总结，不需要用户私有数据」）正对应这个形状，实测也稳定
                // 落到 direct_answer；换成 bookkeeping.report 则会被路由到
                // single_tool_action 的 query_ledger，拿回一个空账本而不是一段话。
                declared = Declared(intent = INTENT_SUMMARY, authoritative = true),
                // 声明 financial 是如实的（prompt 里确实带着用户的收支数字），
                // 而实测这条声明不影响它落到直达路径。
                constraints = Constraints(
                    modePreference = Constraints.MODE_SYNC,
                    dataSensitivity = Constraints.SENSITIVITY_FINANCIAL,
                ),
                client = ClientInfo(
                    app = CLIENT_APP,
                    appVersion = BuildConfig.VERSION_NAME,
                    supportsSse = true,
                    supportsLastEventId = true,
                ),
            ),
        )
        summaryOutcomeOf(accepted.taskId, awaitTerminal(accepted))
    } catch (e: CancellationException) {
        // 取消必须往上抛：吞掉它，外层的超时与协程取消就都失效了
        throw e
    } catch (e: Exception) {
        // 连不上、超时、回包解不出来——对界面都是同一件事：这次拿不到 AI 分析，
        // 退回本地模板即可，不该弹一个用户无能为力的错误框
        AiSummaryOutcome.Unavailable(e.message ?: "调度层不可用")
    }

    /**
     * 等到终态。`sync` 之下 `submitTask` 通常已经把任务跑完才返回，
     * 但路由仲裁可能把它提升为异步（契约明写这个行为），那就得轮询。
     */
    private suspend fun awaitTerminal(accepted: TaskAccepted): TaskSnapshot? {
        var snapshot = runCatching { client.getTask(accepted.taskId) }.getOrNull()
        var attempts = 0
        while (snapshot != null && !snapshot.isTerminal && attempts < MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            snapshot = runCatching { client.getTask(accepted.taskId) }.getOrNull()
            attempts++
        }
        return snapshot
    }

    private companion object {
        const val CLIENT_APP = "bookkeeping"

        /**
         * 契约词表里的类型，不是界面自己起的名字（与 `INTENT_LEDGER_QUERY` 同类）。
         * 选它的依据是路由的 `when` 散文：直答只接「不需要用户私有数据、单步无副作用」
         * 的请求，而我们的数据随 prompt 走。
         */
        const val INTENT_SUMMARY = "chat.explain"

        /**
         * 给 30 秒。实测一次直答 2–6 秒，但 cheap 档位的上游经常抖动
         * （dispatcher 那侧 8 秒就判超时），所以这里留出重试之外的余量；
         * 用户是对着一张骨架屏等，不宜更久。
         */
        const val TIMEOUT_MS = 30_000L

        const val POLL_INTERVAL_MS = 700L
        const val MAX_POLLS = 40
    }
}

/**
 * 终局快照 → 小结结局。**所有终态都从这里出**，不在两处各判一次，
 * 否则两处口径一漂就会给出互相矛盾的结论。
 *
 * 单独抽出来是为了可测：它不含任何 I/O，却能盖住真正要命的几条判断——
 * 任务失败、快照取不到、成功但产物里没有 `answer`，都必须走降级而不是
 * 拿一句编的话糊过去。
 */
internal fun summaryOutcomeOf(taskId: String, snapshot: TaskSnapshot?): AiSummaryOutcome {
    if (snapshot == null) {
        return AiSummaryOutcome.Unavailable("任务已经提交，但读不到它的状态——多半是网络断了")
    }
    if (snapshot.status != TaskSnapshot.STATUS_SUCCEEDED) {
        return AiSummaryOutcome.Unavailable(
            snapshot.error?.detail ?: snapshot.error?.title ?: "任务停在「${snapshot.status}」",
        )
    }
    val answer = parseSummaryAnswer(snapshot.artifacts)
        ?: return AiSummaryOutcome.Unavailable("任务成功了，但产物里没有可读的小结")
    return AiSummaryOutcome.Analyzed(answer.text, answer.confidence, taskId)
}
