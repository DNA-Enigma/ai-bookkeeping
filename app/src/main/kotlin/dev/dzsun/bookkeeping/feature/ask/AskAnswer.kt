package dev.dzsun.bookkeeping.feature.ask

import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.LedgerQueryResult
import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一屏问账的答案。
 *
 * [headline] 是「问什么答什么」的那一句，[detail] 是展开的分组明细。
 * 全是展示口径，**数字一个都不在这里算**——它们来自
 * [dev.dzsun.bookkeeping.core.ledger.LedgerQueryRunner] 在本机 SQL 里聚合出的
 * [LedgerQueryResult]。这里只负责把它说成中文。
 */
data class AskAnswer(
    val question: String,
    val headline: String,
    val detail: List<String>,
    /** 口径说明。金额是从哪个范围、哪个方向、哪个币种算出来的，写在用户看得见的地方。 */
    val footnote: String?,
)

/**
 * 查询结果 → 中文答案。纯函数、无 Android 依赖，边界（零笔、跨年、只有部分分组）都能直接单测。
 *
 * **为什么把「说人话」单独拎出来**：这一层最容易悄悄出错，而错了又最难看出来的地方
 * 就是措辞——「本月支出 ¥0.00，共 0 笔」和「本月没有支出记录」是两个数字完全相同、
 * 含义完全不同的句子。前者会让用户以为记了账只是金额是零。
 *
 * [today] 显式传入而不是内部取 `LocalDate.now()`：否则「本月」这个词的含义
 * 会随运行时刻变化，测试也就没法钉死。
 */
object AskAnswerFormatter {

    fun format(
        question: String,
        query: LedgerQuery,
        result: LedgerQueryResult,
        today: LocalDate,
    ): AskAnswer {
        val scope = scopeOf(query, today)

        val headline = if (result.entryCount == 0) {
            "$scope：没有记录"
        } else {
            "$scope ${result.total.format()}，共 ${result.entryCount} 笔"
        }

        val detail = result.groups.map { group ->
            "${group.key} ${group.amount.format()} · ${group.entryCount} 笔"
        }

        return AskAnswer(
            question = question,
            headline = headline,
            detail = detail,
            footnote = footnoteOf(query, result),
        )
    }

    /** 「本月支出（餐饮）」「近三个月…不含」——把这一问的三个限定条件说全。 */
    private fun scopeOf(query: LedgerQuery, today: LocalDate): String {
        val range = describeRange(query.fromEpochDay, query.toEpochDay, today)
        val direction = when (query.direction) {
            QueryDirection.EXPENSE -> "支出"
            QueryDirection.INCOME -> "收入"
            QueryDirection.BOTH -> "收支净额"
        }
        val filter = when {
            query.categoryName != null -> "（${query.categoryName}）"
            query.merchant != null -> "（商户含「${query.merchant}」）"
            else -> ""
        }
        return "$range$direction$filter"
    }

    private fun footnoteOf(query: LedgerQuery, result: LedgerQueryResult): String? {
        val notes = mutableListOf<String>()

        // 分组被上限截断时要说出来，否则用户以为这就是全部——他只是看到了前几名，
        // 而合计是对的，两者摆在一起会让人怀疑数字对不上
        if (result.groups.size >= query.limit && query.grouping != QueryGrouping.NONE) {
            notes += "分组只显示金额最高的 ${query.limit} 项，合计不受影响"
        }
        if (query.direction == QueryDirection.BOTH) {
            notes += "收支净额 = 支出 − 收入，正数表示这个区间花得比挣得多"
        }
        notes += "金额按 ${result.total.currency} 合计，其他币种的记录不计入（不跨币种相加）"

        return notes.joinToString("；")
    }

    /**
     * 日期区间说成中文。
     *
     * 与「本月」「上月」比对时**按整天对齐**（`atDay(1)` / `atEndOfMonth()`），
     * 而不是只看年月——用户若问的是 10 月 1 日到 10 月 15 日，说成「本月」就把范围说大了。
     */
    internal fun describeRange(fromEpochDay: Long?, toEpochDay: Long?, today: LocalDate): String {
        if (fromEpochDay == null && toEpochDay == null) return "全部时间"

        val from = fromEpochDay?.let(LocalDate::ofEpochDay)
        val to = toEpochDay?.let(LocalDate::ofEpochDay)

        val thisMonth = YearMonth.from(today)
        if (from == thisMonth.atDay(1) && to == thisMonth.atEndOfMonth()) return "本月"
        val lastMonth = thisMonth.minusMonths(1)
        if (from == lastMonth.atDay(1) && to == lastMonth.atEndOfMonth()) return "上月"

        return when {
            from != null && to != null -> "${from} ~ ${to}"
            from != null -> "$from 起"
            else -> "截至 $to"
        }
    }
}
