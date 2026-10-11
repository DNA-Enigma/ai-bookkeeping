package dev.dzsun.bookkeeping.core.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * 一句问账里的时间范围 —— **符号，不是日期**。
 *
 * ## 为什么要有这一层
 *
 * 时间范围有两个产出方：本机模型（判定这一步，吐符号）和本地词法
 * （[LocalQueryTranslator]，认中文时间词）。两边最终都要落到同一段
 * 「epoch day 闭区间」上，而**日期算术只能有一份**：让 1.7B 模型自己算
 * 「这个月」是几号到几号，实测直接算成了上个月（服务端同款 bug）；
 * 而两处各写一份的话，同一天在两个入口问「这个月花了多少」会得到不同的答案。
 *
 * 所以词汇表与 [span] 都在这里，模型只负责说「this_month」这个词，
 * 换算成日期永远是本地这段代码干的活。
 *
 * ## 为什么默认是 [ALL] 而不是「本月」
 *
 * 没有时间词时**不设限**。「一共花了多少」是合法的问法，拿「本月」当默认值
 * 会把「去年花了多少」静默答成本月——答错比答不出来伤得多。
 */
enum class QueryRange(vararg val words: String) {
    TODAY("今天", "今日"),
    YESTERDAY("昨天", "昨日"),
    DAY_BEFORE_YESTERDAY("前天"),
    THIS_WEEK("本周", "这周", "这个星期", "本星期"),
    LAST_WEEK("上周", "上个星期", "上星期"),
    THIS_MONTH("这个月", "本月", "当月", "这个月份"),
    LAST_MONTH("上个月", "上月"),
    THIS_YEAR("今年", "本年", "这一年"),
    LAST_YEAR("去年", "上一年"),

    /**
     * 不限时间。
     *
     * **它故意没有中文词。** 词法路径靠扫 [words] 认范围，给 [ALL] 挂上
     * 「一共」「全部」会改变既有识别结果（那些词本来由
     * [LocalQueryTranslator] 的剥词表处理）；模型路径认它走
     * [resolveQueryRange] 里单独的一份同义词。
     */
    ALL(),
    ;

    /**
     * 这一段覆盖的 epoch day **闭区间**；[ALL] 不设限，返回 null。
     *
     * 区间端点与 `AskAnswerFormatter` 认「本月」的口径一致（1 号到月末、
     * 周一~周日、1 月 1 日~12 月 31 日）——那边要求两端完全相等才叫「本月」，
     * 这里就得给出那样的两端，否则答案里会写「本月」而数字来自别的区间。
     */
    fun span(today: LocalDate): LongRange? = when (this) {
        TODAY -> days(today, today)
        YESTERDAY -> days(today.minusDays(1), today.minusDays(1))
        DAY_BEFORE_YESTERDAY -> days(today.minusDays(2), today.minusDays(2))
        THIS_WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            days(monday, monday.plusDays(6))
        }
        LAST_WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            days(monday, monday.plusDays(6))
        }
        THIS_MONTH -> YearMonth.from(today).let { days(it.atDay(1), it.atEndOfMonth()) }
        LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { days(it.atDay(1), it.atEndOfMonth()) }
        THIS_YEAR -> days(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))
        LAST_YEAR -> today.minusYears(1)
            .let { days(it.withDayOfYear(1), it.withDayOfYear(it.lengthOfYear())) }
        ALL -> null
    }

    companion object {
        /** 带日期的那些范围 —— 词法路径只扫这一批（见 [ALL] 的注释）。 */
        val dated: List<QueryRange> = entries.filter { it != ALL }

        /**
         * 把一个符号（模型给的 `range` 字段，或中文时间词）翻成范围。
         *
         * **三态是刻意的**：
         *  - 没给 / 给了空串 → [ALL]（不设限，见类注释）；
         *  - 认得出来 → 对应的范围；
         *  - **给了但认不出来 → null**，调用方据此判定「模型输出不合法」并走规则兜底。
         *    这不是洁癖：把认不出来的词静默当 [ALL]，用户问「这个月花了多少」会拿到
         *    一个有史以来的总数，而界面上没有任何东西提示他答的不是这个问题。
         */
        fun resolve(raw: String?): QueryRange? {
            val s = raw?.trim() ?: return ALL
            if (s.isEmpty()) return ALL
            if (s.lowercase() in ALL_WORDS) return ALL

            val key = s.lowercase().replace('-', '_').replace(' ', '_')
            dated.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it }
            return dated.firstOrNull { range -> range.words.any { it == s } }
        }

        /**
         * 不限时间的同义词。只服务 [resolve]（模型路径），**不进 [dated] 的扫词表**——
         * 理由见 [ALL] 的注释。
         */
        private val ALL_WORDS = setOf(
            "all", "any", "none", "全部", "所有", "一共", "总共", "合计", "不限", "没有限定",
        )

        private fun days(from: LocalDate, to: LocalDate) = from.toEpochDay()..to.toEpochDay()
    }
}
