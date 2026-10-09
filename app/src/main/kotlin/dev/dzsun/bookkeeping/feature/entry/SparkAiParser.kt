package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.llm.SparkSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import android.util.Log

/** 一次解析的**来源**。评测试卷要能分辨「模型答的」和「规则兜底答的」，否则数据是假的。 */
enum class ParseSource { MODEL, RULES }

data class ParseOutcome(
    val entries: List<ParsedEntry>,
    val source: ParseSource,
    val rawModelOutput: String?,
    val elapsedMs: Long,
)

/**
 * 端侧模型解析器 —— 口语 → 待确认账目。
 *
 * **这是产品的主路径**：赛题硬性要求端侧、不走云端，所以 [AiParser] 的实现换成它，
 * 而不是原来那个只保证 UI 能跑的规则版。
 *
 * 三条口径：
 *  1. **分类是数据不是代码**：可选分类来自应用自己的科目表（`default_accounts.json`），
 *     模型只能在里面选；选不出来就落到「其他支出/其他收入」，不自造分类名。
 *  2. **模型不可用要能降级**：找不到模型、加载失败、输出不是合法 JSON、超时——
 *     一律回落 [LocalAiParser]，用户照样能记账，只是 [ParseSource] 会标成 `RULES`。
 *  3. **结果必须留痕**：[rawModelOutput] 原样保存，排查质量问题时不用猜。
 */
@Singleton
class SparkAiParser @Inject constructor(
    private val session: SparkSession,
) : AiParser {

    private val fallback = LocalAiParser()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 分类表统一由 SparkSession 持有：它同时进系统提示，对话/解析共用同一份，
    // 否则两处提示词不一致会触发重新加载（模拟器实测 65 秒）。
    private val expenseCategories: List<String> get() = session.expenseCategories
    private val incomeCategories: List<String> get() = session.incomeCategories

    override suspend fun parse(raw: String): List<ParsedEntry> = parseWithSource(raw).entries

    /** 带来源的解析：批量评估用它，才能把「模型答的」和「规则兜底的」分开统计。 */
    suspend fun parseWithSource(
        raw: String,
        initTimeoutMs: Long = INIT_TIMEOUT_MS,
        genTimeoutMs: Long = PARSE_TIMEOUT_MS,
    ): ParseOutcome {
        if (raw.isBlank()) return ParseOutcome(emptyList(), ParseSource.RULES, null, 0L)

        val t0 = System.currentTimeMillis()

        // 初始化（读模型 + 解码系统提示）单独给宽超时：系统提示里带分类表，
        // 模拟器实测要 60s+ 才解码完；用生成超时去卡它会把初始化掐死，
        // 引擎卡在 ProcessingSystemPrompt，之后每条都 1ms 快速失败、全落规则兜底。
        val ready = withTimeoutOrNull(initTimeoutMs) {
            runCatching { session.ensureReady() }.getOrDefault(false)
        } ?: false
        val initMs = System.currentTimeMillis() - t0

        val modelOut = if (ready) {
            withTimeoutOrNull(genTimeoutMs) {
                runCatching {
                    session.complete(SparkSession.MODE_PARSE + raw, SparkSession.DEFAULT_MAX_TOKENS)
                }.getOrNull()
            }
        } else null
        val elapsed = System.currentTimeMillis() - t0
        if (!ready) Log.w(TAG, "初始化未就绪(超时上限 ${initTimeoutMs}ms, 实际 ${initMs}ms)")

        if (modelOut.isNullOrBlank()) {
            Log.i(TAG, "模型不可用/超时(${elapsed}ms)，回落规则解析")
            return ParseOutcome(fallback.parse(raw), ParseSource.RULES, modelOut, elapsed)
        }

        val entries = runCatching { decode(modelOut) }.getOrNull()
        if (entries == null) {
            Log.w(TAG, "模型输出不是合法 JSON，回落规则解析: ${modelOut.take(200)}")
            return ParseOutcome(fallback.parse(raw), ParseSource.RULES, modelOut, elapsed)
        }
        return ParseOutcome(entries, ParseSource.MODEL, modelOut, elapsed)
    }

    /** 取输出里第一个完整数组段（模型偶尔会在前后加一句话）。 */
    private fun extractArray(text: String): String? {
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        return if (s in 0 until e) text.substring(s, e + 1) else null
    }

    /**
     * 解析模型输出里的账目数组。**公开给阿账对话复用** ——
     * 对话模式下模型也可能直接吐数组（用户其实是在报一笔账）。
     *
     * @return null = 输出里没有合法数组（不是「空数组」，是解析不了）
     */
    fun decodeEntries(output: String): List<ParsedEntry>? = decode(output)

    private fun decode(output: String): List<ParsedEntry>? {
        val arr = extractArray(output) ?: return null
        val parsed = runCatching { json.decodeFromString<List<ModelEntry>>(arr) }.getOrNull() ?: return null
        return parsed.mapIndexedNotNull { i, m ->
            val amount = m.amount ?: return@mapIndexedNotNull null
            if (amount <= 0.0) return@mapIndexedNotNull null
            val income = m.kind.equals("income", true)
            val rawCategory = m.category.orEmpty()
            // 分类名必须能在科目表里按名字找到，否则确认入账时映射会落空
            val category = when {
                income && rawCategory in incomeCategories -> rawCategory
                !income && rawCategory in expenseCategories -> rawCategory
                income -> "其他收入"
                else -> "其他支出"
            }
            ParsedEntry(
                id = "spark-$i",
                kind = if (income) EntryKind.INCOME else EntryKind.EXPENSE,
                amountText = if (amount % 1.0 == 0.0) amount.toLong().toString()
                else amount.toString(),
                categoryName = category,
                payee = m.payee.orEmpty().ifBlank { category },
                note = m.note.orEmpty().ifBlank { category },
                dateOffsetDays = 0,
                confidence = (m.confidence ?: 0.7f).coerceIn(0f, 1f),
            )
        }
    }

    @Serializable
    private data class ModelEntry(
        val kind: String? = null,
        val amount: Double? = null,
        val category: String? = null,
        val note: String? = null,
        val payee: String? = null,
        val confidence: Float? = null,
    )

    companion object {
        private const val TAG = "SparkAiParser"

        /** 单句**生成**上限。超时就降级规则——界面不能一直转圈。 */
        const val PARSE_TIMEOUT_MS = 90_000L

        /**
         * 初始化（加载模型 + 解码系统提示）上限。
         * 模拟器实测系统提示解码要 60s+，240s 是留余量；真机应远快于此。
         */
        const val INIT_TIMEOUT_MS = 240_000L
    }
}
