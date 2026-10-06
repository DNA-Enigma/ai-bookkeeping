package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.money.Money

/** 一条待写入的分录。 */
data class PostingDraft(
    val accountId: String,
    val amount: Money,
)

/**
 * 一笔记账事件的草稿。
 *
 * 构造即校验，因此**不满足复式不变式的凭证在类型层面就无法存在**——
 * 仓库层不需要再防一次，调用方也不可能绕过。
 *
 * 三条规则：
 * 1. 至少两条分录（单条分录的"支出"没有对手方，统计不出净额）；
 * 2. 同一凭证内币种一致（跨币种需要汇率与本位币概念，尚未引入）；
 * 3. 所有分录金额之和为零。
 *
 * 第 3 条是复式的全部意义：它让转账不会双倍计入收支统计，让退款能冲减原分类，
 * 让"这个月花了多少"只需把 EXPENSE 侧分录求和。
 */
data class JournalDraft(
    val dateEpochDay: Long,
    val payee: String?,
    val note: String?,
    val source: JournalSource,
    val postings: List<PostingDraft>,
) {
    init {
        require(postings.size >= 2) {
            "复式记账至少需要两条分录，实际 ${postings.size} 条"
        }
        require(postings.all { it.accountId.isNotBlank() }) {
            "每条分录都必须指定账户"
        }
        val currencies = postings.map { it.amount.currency }.distinct()
        require(currencies.size == 1) {
            "同一凭证内币种必须一致，实际为 $currencies"
        }
        val sum = postings.fold(0L) { acc, posting -> Math.addExact(acc, posting.amount.amountMinor) }
        require(sum == 0L) {
            "复式记账要求分录金额之和为零，实际为 $sum 个最小单位：" +
                postings.joinToString { "${it.accountId}=${it.amount.toPlainString()}" }
        }
    }

    val currency: String get() = postings.first().amount.currency

    companion object {
        /** 支出：资产减少，支出分类增加。 */
        fun expense(
            dateEpochDay: Long,
            amount: Money,
            fromAccountId: String,
            categoryAccountId: String,
            payee: String? = null,
            note: String? = null,
            source: JournalSource = JournalSource.MANUAL,
        ): JournalDraft = JournalDraft(
            dateEpochDay = dateEpochDay,
            payee = payee,
            note = note,
            source = source,
            postings = listOf(
                PostingDraft(fromAccountId, -amount),
                PostingDraft(categoryAccountId, amount),
            ),
        )

        /** 收入：资产增加，收入分类增加。 */
        fun income(
            dateEpochDay: Long,
            amount: Money,
            toAccountId: String,
            categoryAccountId: String,
            payee: String? = null,
            note: String? = null,
            source: JournalSource = JournalSource.MANUAL,
        ): JournalDraft = JournalDraft(
            dateEpochDay = dateEpochDay,
            payee = payee,
            note = note,
            source = source,
            postings = listOf(
                PostingDraft(toAccountId, amount),
                PostingDraft(categoryAccountId, -amount),
            ),
        )

        /** 转账：一侧资产减少，另一侧资产增加，不产生收支。 */
        fun transfer(
            dateEpochDay: Long,
            amount: Money,
            fromAccountId: String,
            toAccountId: String,
            note: String? = null,
            source: JournalSource = JournalSource.MANUAL,
        ): JournalDraft = JournalDraft(
            dateEpochDay = dateEpochDay,
            payee = null,
            note = note,
            source = source,
            postings = listOf(
                PostingDraft(fromAccountId, -amount),
                PostingDraft(toAccountId, amount),
            ),
        )
    }
}
