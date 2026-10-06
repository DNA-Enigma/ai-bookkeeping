package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.money.Money

/** 一条待写入的分录。 */
data class PostingDraft(
    val accountId: String,
    val amount: Money,
)

/**
 * 一条待写入的明细。
 *
 * [amountMinor] 可空：很多小票只给总额，明细那几行没有单价。
 * 它也不参与平衡校验——明细只描述，记账看分录。
 */
data class ItemDraft(
    val description: String,
    val amountMinor: Long? = null,
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

    /** 默认已核对。AI 抽取或导入的结果应显式传 [JournalStatus.PENDING] 等用户核对。 */
    val status: JournalStatus = JournalStatus.CLEARED,

    /** 发生地点。回想时的锚点。 */
    val place: String? = null,

    /** 外部流水来源，如 `alipay` / `wechat` / `cmb`。与 [externalRef] 成对出现。 */
    val externalSource: String? = null,

    /** 外部流水单号，取「交易单号」（平台侧生成）而非「商户单号」。 */
    val externalRef: String? = null,

    /** 平台侧原始状态（如「已全额退款」）。再导入时靠它比对出变化。 */
    val externalStatus: String? = null,

    /**
     * 这是一笔**冲减**，冲的是哪一笔凭证。
     *
     * 冲减的机制是负向分录（见 [reversalOf]），这个字段只负责**溯源**：
     * 它让「那笔 -38 是哪来的」能回答得出来，也让重复导入不至于冲第二次。
     */
    val reversesJournalId: String? = null,

    /** 小票明细，纯粹是「买了什么」的描述，不参与记账。 */
    val items: List<ItemDraft> = emptyList(),
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
        // 只有单号没有来源是没意义的——去重键是这一对，单缺一个就退化成
        // 「所有单号互相比」，不同机构同号会误判成重复
        require((externalSource == null) == (externalRef == null)) {
            "externalSource 与 externalRef 必须同时给出或同时不给出" +
                "（当前 source=$externalSource, ref=$externalRef）"
        }
        // 状态得挂在某个单号上才可比对：没有单号，"上次的状态"就是无主的值，
        // 再导入时无从知道它是哪一笔的
        require(externalStatus == null || externalRef != null) {
            "externalStatus 必须伴随 externalRef 才有意义"
        }
        require(items.all { it.description.isNotBlank() }) {
            "明细的描述不能为空——空明细行对「想起买了什么」毫无用处"
        }
        require(reversesJournalId == null || reversesJournalId.isNotBlank()) {
            "reversesJournalId 要么不给，要么给出一个真的凭证 id——空串会让溯源查不到任何东西"
        }
    }

    /** 是否来自外部流水。 */
    val isImported: Boolean get() = externalRef != null

    /** 是不是一笔冲减（退款/撤销/冲正）。 */
    val isReversal: Boolean get() = reversesJournalId != null

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

        /**
         * 冲减：把 [original] 的分录**反向**再记一笔。退款、撤销、冲正都是这一件事。
         *
         * **为什么是负向分录，而不是新的类型或标记。**
         * 退款在复式里本来就是「原凭证的反向」，不需要第三样东西：
         * 原支出是「资产 -38 / 支出分类 +38」，退款就是「资产 +38 / 支出分类 -38」。
         * 分录之和照样为零，所以 [JournalDraft] 的构造校验**原样适用**，一行都不用改；
         * 而月度统计只是把支出分类那一侧的分录求和，退款那笔自然把原消费抵消掉。
         *
         * 反过来，若新增 `JournalType.REFUND` 或 `isReversal` 标记，会有两个代价：
         * 一是**标记与分录可以互相矛盾**（一笔标了 REFUND 却记成正向支出），
         * 而分录是唯一被构造校验守住的东西，统计也只读分录，标记不参与任何计算；
         * 二是 `JournalSource` 的语义是**渠道**（手记/拍票/流水/语音），
         * 退款是一种「是什么」，不是一种「从哪来」——同一个渠道既能来一笔消费也能来一笔退款。
         *
         * 所以：**负号是机制，[reversesJournalId] 是溯源**。前者让账平，后者让「这笔 -38 哪来的」
         * 回答得出来，也让重复导入不会冲第二次。
         *
         * [amount] 为 null 表示**全额冲减**（把每条分录原样取反，任意条数都行）；
         * 给出金额表示**部分退款**（如平台状态写「已退款 ¥6.60」），此时原凭证必须是
         * 两条分录的一收一支——部分退款按每条分录各自的符号反向，金额取 [amount]。
         * 三条分录以上的拆分凭证做部分冲减需要按比例摊分，那套规则还没定，宁可拒绝也不猜。
         *
         * [dateEpochDay] 默认取原凭证的日期：流水里「同一行状态变成已退款」**不带退款时间**，
         * 用它自己的消费日期等于把那笔消费在当月冲平，与「这笔最终没花钱」的认知一致。
         * 若退款是独立一行（有自己的日期），传那一行的日期。
         */
        fun reversalOf(
            original: JournalDraft,
            amount: Money? = null,
            dateEpochDay: Long = original.dateEpochDay,
            source: JournalSource = original.source,
            payee: String? = original.payee,
            note: String? = null,
            status: JournalStatus = JournalStatus.CLEARED,
            reversesJournalId: String? = null,
        ): JournalDraft {
            val counterpartyOf: (PostingDraft) -> Money
            if (amount == null) {
                counterpartyOf = { posting -> -posting.amount }
            } else {
                require(amount.currency == original.currency) {
                    "冲减金额的币种必须与原凭证一致：${amount.currency} 与 ${original.currency}"
                }
                require(amount.amountMinor > 0) {
                    "冲减金额要传正数（退回来多少），实际为 ${amount.toPlainString()}"
                }
                require(original.postings.size == 2) {
                    "部分冲减只支持两条分录的凭证（一收一支），" +
                        "实际 ${original.postings.size} 条——拆分凭证要按比例摊分，规则未定，不猜"
                }
                require(amount <= original.postings.first().amount.abs()) {
                    "退款金额 ${amount.toPlainString()} 超过原凭证金额 " +
                        "${original.postings.first().amount.abs().toPlainString()}——多半是配错了原凭证"
                }
                // 原分录收的是负、支的是正，冲减各自反号
                counterpartyOf = { posting ->
                    if (posting.amount.isNegative()) amount else -amount
                }
            }

            return JournalDraft(
                dateEpochDay = dateEpochDay,
                payee = payee,
                note = note,
                source = source,
                status = status,
                postings = original.postings.map { posting ->
                    PostingDraft(posting.accountId, counterpartyOf(posting))
                },
                reversesJournalId = reversesJournalId,
            )
        }
    }
}
