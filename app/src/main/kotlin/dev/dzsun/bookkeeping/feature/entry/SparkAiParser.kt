package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.llm.ReadyResult
import dev.dzsun.bookkeeping.llm.SourceTag
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.issueMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import android.util.Log

/**
 * 一次解析的结果 + **它的来源**。
 *
 * [source] 是统一来源标注（[SourceTag]），评测试卷与界面共用同一份口径：
 * 模型答的写 `src=MODEL`，规则兜底的写 `模型未接上（<原因>），本次按规则处理（src=RULES[，代码]）`。
 * 评测试卷要能分辨「模型答的」和「规则兜底答的」，否则数据是假的。
 */
data class ParseOutcome(
    val entries: List<ParsedEntry>,
    val source: SourceTag,
    val rawModelOutput: String?,
    val elapsedMs: Long,
)

/**
 * 模型输出解码的三态。
 *
 * [NoArray] 与 [AmountMismatch] **必须分开**：前者是「这不是一个账目数组」，
 * 后者是「数组合法但金额与原文对不上」。两者都要回落规则，
 * 但只有后者要在来源里记 `MODEL_AMOUNT_MISMATCH`——验收按这个代码断言。
 */
sealed interface EntryDecode {
    data class Ok(val entries: List<ParsedEntry>) : EntryDecode

    data object NoArray : EntryDecode

    data class AmountMismatch(
        val parsed: java.math.BigDecimal?,
        val expected: List<java.math.BigDecimal>,
    ) : EntryDecode
}

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
 *     一律回落 [LocalAiParser]，用户照样能记账，只是 [ParseOutcome.source] 会标成 `RULES`（带原因）。
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
        if (raw.isBlank()) {
            return ParseOutcome(emptyList(), SourceTag.rules("这句话是空的"), null, 0L)
        }

        val t0 = android.os.SystemClock.elapsedRealtime()

        // 初始化（读模型 + 解码系统提示）单独给宽超时：系统提示里带分类表，
        // 模拟器实测要 60s+ 才解码完；用生成超时去卡它会把初始化掐死，
        // 引擎卡在 ProcessingSystemPrompt，之后每条都 1ms 快速失败、全落规则兜底。
        val readyResult = withTimeoutOrNull(initTimeoutMs) { session.ensureReady() }
        val ready = readyResult is ReadyResult.Ready
        val initMs = android.os.SystemClock.elapsedRealtime() - t0

        val modelOut = if (ready) {
            withTimeoutOrNull(genTimeoutMs) {
                runCatching {
                    session.complete(SparkSession.MODE_PARSE + raw, SparkSession.DEFAULT_MAX_TOKENS)
                }.getOrNull()
            }
        } else null
        val elapsed = android.os.SystemClock.elapsedRealtime() - t0

        // 没就绪的原因要如实上报：用户侧照旧降级规则解析，但来源标注上得写清
        // 「是模型没接上」而不是一句没有原因的「按规则处理」。
        val notReadyReason = when (readyResult) {
            null -> "模型还在准备就没等到（首次约 1~2 分钟，上限 ${initTimeoutMs / 1000} 秒）"
            is ReadyResult.Failed -> issueMessage(readyResult.issue)
            is ReadyResult.Ready -> null
        }
        if (notReadyReason != null) {
            Log.w(TAG, "初始化未就绪(超时上限 ${initTimeoutMs}ms, 实际 ${initMs}ms)：$notReadyReason")
            return fallback(raw, modelOut, elapsed, notReadyReason, null)
        }

        if (modelOut.isNullOrBlank()) {
            Log.i(TAG, "模型不可用/超时(${elapsed}ms)，回落规则解析")
            return fallback(raw, modelOut, elapsed, "模型这次没有输出任何内容", SourceTag.CODE_BAD_OUTPUT)
        }

        return when (val decoded = decodeChecked(raw, modelOut)) {
            is EntryDecode.Ok -> ParseOutcome(decoded.entries, SourceTag.Model, modelOut, elapsed)

            EntryDecode.NoArray -> {
                Log.w(TAG, "模型输出不是合法 JSON，回落规则解析: ${modelOut.take(200)}")
                fallback(raw, modelOut, elapsed, "模型输出不是合法 JSON", SourceTag.CODE_BAD_OUTPUT)
            }

            is EntryDecode.AmountMismatch -> {
                // 真机实测：「午餐20元」被模型报成 30。**记错金额比记不上更糟**——
                // 记不上用户会重来，记错了要等到对账才发现，那时已经不知道哪笔是错的。
                // 所以这条模型结果**整批作废**，以规则为准。
                Log.w(
                    TAG,
                    "金额与原文对不上，作废模型结果：模型报 ${decoded.parsed}，" +
                        "原文里的金额是 ${decoded.expected}（$raw）",
                )
                fallback(
                    raw,
                    modelOut,
                    elapsed,
                    AmountFidelity.describe(decoded.parsed, decoded.expected),
                    SourceTag.CODE_AMOUNT_MISMATCH,
                )
            }
        }
    }

    /**
     * 回落规则解析，并把**具体原因**带进来源标注。
     *
     * suspend 是因为 [AiParser.parse] 在接口上就是挂起函数（[LocalAiParser] 的
     * 规则实现本身不挂起，但接口统一了签名，调用点只能在协程里）。
     * 调用方 [parseWithSource] 本来就是 suspend，链路自然成立。
     */
    private suspend fun fallback(
        raw: String,
        modelOut: String?,
        elapsed: Long,
        reason: String,
        code: String?,
    ) = ParseOutcome(fallback.parse(raw), SourceTag.rules(reason, code), modelOut, elapsed)

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

    /**
     * 解码 **+ 金额保真校验**。[raw] 是用户原话，[output] 是模型产物。
     *
     * 对话页与记账解析共用这一份：多一条入口就多一次「忘了校验」的机会，
     * 而漏校验的后果是**一个错金额被静默写进账本**。
     */
    fun decodeChecked(raw: String, output: String): EntryDecode {
        val entries = decode(output) ?: return EntryDecode.NoArray
        // 数组是空的 = 模型判定「这不是一笔账」，与「有账但金额错」是两回事
        if (entries.isEmpty()) return EntryDecode.Ok(entries)

        entries.forEach { entry ->
            when (val check = AmountFidelity.check(raw, entry.amountText)) {
                is AmountFidelity.Result.Mismatch ->
                    return EntryDecode.AmountMismatch(check.parsed, check.expected)
                // 原文没有数字 → 无从校验，放行（不能因为没法校验就把账拦下）
                AmountFidelity.Result.Match, AmountFidelity.Result.NothingToCheck -> Unit
            }
        }
        return EntryDecode.Ok(entries)
    }

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
