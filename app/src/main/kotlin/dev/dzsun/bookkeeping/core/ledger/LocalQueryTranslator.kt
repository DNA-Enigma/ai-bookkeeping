package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把一句中文问账翻成一次可本地执行的 [LedgerQuery]——**这是降级路径，不是主路径**。
 *
 * ## 为什么需要它，以及它为什么是降级的
 *
 * 问账的正路是调度层判定（`bookkeeping.ledger.query`）→ 下发结构化查询 → 本机算。
 * 但调度层**当前产不出这个结构**：那个账目查询工具读的是它自己的 `LedgerPort`
 * （参考实现是进程内的内存账本），实测恒返回 `{"entries": [], "count": 0}`，
 * 于是 `LedgerQueryClient` 每次都落进 `Unavailable`。也就是说这条意图在契约补齐前
 * **一句话也答不出来**。
 *
 * 这一层就是那时的退路，与拍照路径的 `LocalAiParser` 同一个位置、同一个性质：
 * **调度层在的时候优先走调度层，只有它给不出查询时才落到这里**。
 * 后端把 `bookkeeping.ledger.query` 的结构定下来之后，这条路会自动退居二线，
 * 不需要改任何界面代码。
 *
 * ## 为什么是词法而不是模型
 *
 * 它解决的是**有限的一小组句式**（时间词 + 收支方向 + 商户/分类），
 * 而不是开放的自然语言理解。这是刻意的：一个只在本地跑的模型没法测试、
 * 也没法解释它为什么把「交通银行」认成了交通分类；而一张有界的词表配上
 * 逐条的边界单测，错了能一眼看出错在哪一条。
 *
 * ## 它**不**做什么
 *
 * - **不猜方向**之外的字段。日期认不出来就不设限（「一共花了多少」是合法的问法），
 *   而不是拿「这个月」当默认值——那会把「去年花了多少」静默答成本月。
 * - **不硬编码分类名**（约定 3）。分类名从科目表读（见 [translate]），
 *   用户改了分类，这里跟着改，代码一个字不用动。
 * - **认不出来就返回 null**，交给界面如实说「翻译不了」，绝不拿一个空结果
 *   冒充「你没花过钱」。
 *
 * ## 已知的弱点（写在这里，免得被当成 bug 反复报）
 *
 * 中文没有词边界，所以「交通银行」里含分类名「交通」时会被认成**分类**而不是商户。
 * 这是取长匹配（优先认最长的分类名）的代价，也是它会退居二线的理由之一。
 */
@Singleton
class LocalQueryTranslator @Inject constructor(
    private val database: LedgerDatabase,
) {

    /**
     * 翻一句问账。[today] 显式传入而不是内部取 `LocalDate.now()`：
     * 「这个月」「上周」的含义随今天而变，测试也就没法定死（与
     * [dev.dzsun.bookkeeping.feature.ask.AskAnswerFormatter] 同一个理由）。
     *
     * 分类名从科目表读，**不写死**——读到读不到都不影响查询本身，
     * 读不到时只是没法把某个词认成分类，它会退化成商户包含匹配。
     */
    suspend fun translate(question: String, today: LocalDate): LedgerQuery? {
        val categoryNames = runCatching {
            database.accountDao()
                .findByTypes(listOf(AccountType.EXPENSE, AccountType.INCOME))
                .map { it.name }
                .toSet()
        }.getOrDefault(emptySet())

        return parseQuery(question, today, categoryNames)
    }
}

/**
 * 翻译的纯函数核。**没有 Android、没有数据库、没有当前时间**——
 * 三样都从参数进来，所以每一条词法规则都能在纯 JVM 测试里逐条钉死。
 */
internal fun parseQuery(
    question: String,
    today: LocalDate,
    categoryNames: Set<String> = emptySet(),
): LedgerQuery? {
    val text = question.trim()
    if (text.isEmpty()) return null

    val direction = matchDirection(text)
    val interrogative = firstMatch(text, INTERROGATIVES)

    // **一道判据：光有时间词不算一次问账。**「今天天气」里有「今天」，
    // 但没有任何"要算什么"的信号；放行的话会拿「天气」当商户名去查，
    // 答一句「没有记录」，看起来像答了其实答非所问。
    if (direction == null && interrogative == null) return null

    val range = matchRange(text, today)

    // 分类先认，**认完从文本里摘掉**再找商户：不然「其他支出花了多少」里的
    // 「支出」会被当成方向词剥掉，剩下「其他」被误当商户名。
    val category = longestCategoryIn(text, categoryNames)
    val remainder = if (category == null) text else text.replace(category, " ")

    val merchant = extractSubject(remainder)

    return LedgerQuery(
        fromEpochDay = range?.from,
        toEpochDay = range?.to,
        direction = direction ?: QueryDirection.EXPENSE,
        categoryName = category,
        merchant = merchant,
        grouping = matchGrouping(text) ?: QueryGrouping.NONE,
    )
}

// —— 方向 ——

/**
 * 收 / 支 / 净额。
 *
 * 只认词表里明确出现的词；**一个都没有时交给调用方兜底成支出**，
 * 因为这个降级路径存在的意义就是答最常见的问法（「…花了多少」）。
 * 主路径（调度层）没有这个兜底——那里的 `direction` 是必填，缺了整条查询不成立。
 */
private fun matchDirection(text: String): QueryDirection? {
    if (firstMatch(text, BOTH_WORDS) != null) return QueryDirection.BOTH

    val incomeAt = firstMatch(text, INCOME_WORDS)?.let(text::indexOf) ?: Int.MAX_VALUE
    val expenseAt = firstMatch(text, EXPENSE_WORDS)?.let(text::indexOf) ?: Int.MAX_VALUE

    return when {
        incomeAt == Int.MAX_VALUE && expenseAt == Int.MAX_VALUE -> null
        // 两个都出现时（「支出和收入各多少」）取先说的那个
        incomeAt < expenseAt -> QueryDirection.INCOME
        else -> QueryDirection.EXPENSE
    }
}

// —— 时间 ——

/** 一段闭区间，单位为 epoch day，与 `journal.dateEpochDay` 同一口径。 */
private data class Range(val from: Long, val to: Long)

/** `近三个月` / `最近 7 天`。放在固定词表之前，因为它带数字。 */
private val RELATIVE =
    Regex("(?:最近|近)\\s*([0-9一二三四五六七八九十]+)\\s*(个月|个星期|周|天)")

private fun matchRange(text: String, today: LocalDate): Range? {
    RELATIVE.find(text)?.let { m ->
        val n = parseCount(m.groupValues[1]) ?: return@let
        return when (m.groupValues[2]) {
            // 「近 N 天」含今天，所以是 N-1 天前到今天，一共 N 天
            "天" -> Range(today.minusDays((n - 1).toLong()).toEpochDay(), today.toEpochDay())
            // 「近 N 个月」从 N-1 个月前的 1 号起算——「近三个月」是本月 +
            // 往前两个整月，不是从今天倒推 90 天
            "个月" -> Range(
                YearMonth.from(today).minusMonths((n - 1).toLong()).atDay(1).toEpochDay(),
                today.toEpochDay(),
            )
            else -> Range(
                today.minusWeeks((n - 1).toLong()).toEpochDay(),
                today.toEpochDay(),
            )
        }
    }

    for (rule in FIXED_RANGES) {
        val hit = firstMatch(text, rule.words) ?: continue
        return rule.span(today)
    }
    return null
}

/**
 * 固定时间词。
 *
 * **按整段对齐**（周一~周日、1 号~月末、1 月 1 日~12 月 31 日），与
 * `AskAnswerFormatter.describeRange` 认「本月」的口径一致——那边要求
 * `atDay(1)` / `atEndOfMonth()` 完全相等才叫「本月」，这里就得给出那样的两端。
 * 各规则互不为子串，顺序不影响结果。
 */
private class FixedRange(val words: List<String>, val span: (LocalDate) -> Range)

private fun days(from: LocalDate, to: LocalDate) = Range(from.toEpochDay(), to.toEpochDay())

private val FIXED_RANGES = listOf(
    FixedRange(listOf("今天", "今日")) { days(it, it) },
    FixedRange(listOf("昨天", "昨日")) { days(it.minusDays(1), it.minusDays(1)) },
    FixedRange(listOf("前天")) { days(it.minusDays(2), it.minusDays(2)) },
    FixedRange(listOf("本周", "这周", "这个星期", "本星期")) {
        val monday = it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        days(monday, monday.plusDays(6))
    },
    FixedRange(listOf("上周", "上个星期", "上星期")) {
        val monday = it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        days(monday, monday.plusDays(6))
    },
    FixedRange(listOf("这个月", "本月", "当月", "这个月份")) {
        val m = YearMonth.from(it); days(m.atDay(1), m.atEndOfMonth())
    },
    FixedRange(listOf("上个月", "上月")) {
        val m = YearMonth.from(it).minusMonths(1); days(m.atDay(1), m.atEndOfMonth())
    },
    FixedRange(listOf("今年", "本年", "这一年")) {
        days(it.withDayOfYear(1), it.withDayOfYear(it.lengthOfYear()))
    },
    FixedRange(listOf("去年", "上一年")) {
        val y = it.minusYears(1); days(y.withDayOfYear(1), y.withDayOfYear(y.lengthOfYear()))
    },
)

/** 中文数字与阿拉伯数字都认，覆盖 1~99——问账里见不到比这更大的跨度。 */
private fun parseCount(raw: String): Int? {
    raw.trim().toIntOrNull()?.let { return it.takeIf { it > 0 } }

    val text = raw.trim()
    if (text.isEmpty()) return null
    val tensAt = text.indexOf('十')
    if (tensAt < 0) {
        val d = text.singleOrNull()?.let(CN_DIGITS::get) ?: return null
        return d.takeIf { it > 0 }
    }
    val tens = text.substring(0, tensAt)
        .ifEmpty { "一" }
        .singleOrNull()?.let(CN_DIGITS::get) ?: return null
    val ones = text.substring(tensAt + 1)
        .ifEmpty { return tens * 10 }
        .singleOrNull()?.let(CN_DIGITS::get) ?: return null
    return tens * 10 + ones
}

private val CN_DIGITS = mapOf(
    '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

// —— 分组 ——

private fun matchGrouping(text: String): QueryGrouping? = when {
    firstMatch(text, MONTH_GROUPING) != null -> QueryGrouping.MONTH
    firstMatch(text, CATEGORY_GROUPING) != null -> QueryGrouping.CATEGORY
    firstMatch(text, MERCHANT_GROUPING) != null -> QueryGrouping.MERCHANT
    else -> null
}

// —— 商户 / 分类 ——

/**
 * 从残句里取出商户名。
 *
 * 做法是「把认得的词全摘掉，剩下什么就是什么」——中文没有空格，
 * 逐词剥离是唯一不引分词器的办法。
 *
 * 两道闸门保证它不会把一整句话当成商户名：
 * 太长（多半是句子，不是店名）或含标点/语气词，一律当作**没提取到商户**，
 * 而不是硬塞给查询。宁可少一个筛选条件，也不要拿一句寒暄去查账。
 */
private fun extractSubject(text: String): String? {
    var s = RELATIVE.replace(text, " ")
    REMOVABLE.forEach { s = s.replace(it, " ") }
    val subject = s.filterNot(Char::isWhitespace).trimParticles()

    return subject
        .takeIf { it.isNotEmpty() }
        ?.takeIf { it.length <= MAX_SUBJECT }
        ?.takeIf { candidate -> candidate.none { it in REJECT_CHARS } }
}

/**
 * 问句里出现的最长分类名。取长者是因为分类名会互相包含
 * （「其他支出」含「支出」），先认短的会把长名切碎。
 */
private fun longestCategoryIn(text: String, categoryNames: Set<String>): String? =
    categoryNames
        .filter { it.isNotBlank() && text.contains(it) }
        .maxByOrNull { it.length }

// —— 词表 ——

/** 收 / 支两侧一起说时用的词。**必须先于单侧判定**——「收支」里含着「支出」。 */
private val BOTH_WORDS = listOf("收支", "净额", "结余", "净支出", "净收入", "净花")

private val INCOME_WORDS = listOf("收入", "挣", "赚", "进账", "入账", "收到", "进项")

private val EXPENSE_WORDS =
    listOf("花了", "花销", "花费", "支出", "消费", "开销", "用了", "付款", "付了")

/** 「要算什么」的信号。缺了它、又没有方向词，就不算一次问账（见 [parseQuery]）。 */
private val INTERROGATIVES =
    listOf("多少钱", "多少元", "多少笔", "几次", "几笔", "多少", "多少了", "几次了")

private val MONTH_GROUPING = listOf("按月", "每月", "每个月", "月度", "分月")
private val CATEGORY_GROUPING = listOf("按分类", "按类别", "各分类", "各类别")
private val MERCHANT_GROUPING = listOf("按商户", "按商家", "各家", "各商户")

/**
 * 剥离用：从残句里摘掉的词。分类名不在其中——它前一步已经单独摘过。
 *
 * **这里不要放单字的常用字。**「和」「有」「在」「都」「各」看着像虚词，
 * 但它们真实地出现在店名里（和平饭店、有家便利店、都市丽人），
 * 全局替换会把名字切碎，而切碎之后依然是一个「像样的」商户名，
 * 不会报错，只会静默查不到。单字虚词交给 [trimParticles] 只削两端。
 */
private val REMOVABLE =
    FIXED_RANGES.flatMap { it.words } +
        BOTH_WORDS + INCOME_WORDS + EXPENSE_WORDS + INTERROGATIVES +
        MONTH_GROUPING + CATEGORY_GROUPING + MERCHANT_GROUPING +
        listOf(
            "你好", "请问", "请", "帮我", "帮忙", "麻烦", "我想", "想", "告诉", "知道",
            "看看", "看一下", "一下", "一共", "总共", "合计", "所有", "全部",
            "大概", "大约", "差不多", "分别",
        )

/**
 * 只削两端的虚词与谓语动词（「**在**星巴克」「**看**今年」）。削两端而不是全局替换，
 * 是因为它们出现在店名中间时是名字的一部分（「都市丽人」的「都」不在这个表里，
 * 「和平饭店」的「和」也不在）；出现在两端时才是语气词或「怎么问」的那半句。
 */
private val PARTICLES = setOf('的', '了', '是', '我', '你', '吧', '呢', '在', '看', '去')

private fun String.trimParticles(): String {
    var s = this
    while (s.isNotEmpty() && s.first() in PARTICLES) s = s.drop(1)
    while (s.isNotEmpty() && s.last() in PARTICLES) s = s.dropLast(1)
    return s
}

/** 太长就不是店名了，多半是整句话。 */
private const val MAX_SUBJECT = 12

/** 商户名里不会出现的字符（标点与语气词）。出现即判定「这不是一个名字」。 */
private val REJECT_CHARS = setOf(
    '吗', '呢', '吧', '啊', '呀', '嘛',
    '？', '?', '！', '!', '。', '，', ',', '、', '：', ':', '；', ';', '“', '”', '"', '「', '」',
)

/** 文本里最早出现的那个词。并列时按传入顺序取——所以**更具体的词要排在前面**。 */
private fun firstMatch(text: String, words: List<String>): String? =
    words.filter(text::contains).minByOrNull(text::indexOf)
