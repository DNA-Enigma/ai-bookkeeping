package dev.dzsun.bookkeeping.core.ledger

import androidx.room.withTransaction
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountPeriodBalance
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.BudgetEntity
import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.database.DuplicateCandidate
import dev.dzsun.bookkeeping.core.database.JournalEntity
import dev.dzsun.bookkeeping.core.database.JournalItemEntity
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.database.MonthlyTotal
import dev.dzsun.bookkeeping.core.database.PostingEntity
import dev.dzsun.bookkeeping.core.database.VoucherLine
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import dev.dzsun.bookkeeping.core.platform.Clock
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 账本的读写入口。
 *
 * 写入只接受 [JournalDraft]——它已经在构造时保证分录平衡，
 * 所以这里不需要再防一次，也不可能写进一本不平的账。
 */
@Singleton
class LedgerRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    /** 账目投影：按日期倒序的全部收支记录。 */
    fun observeEntries(): Flow<List<LedgerRow>> = database.journalDao().observeLedgerRows()

    /**
     * 一笔账的列表投影（详情页用）。没有这笔时发 null。
     *
     * 复用 [observeEntries] 的同一口径，不再写一份 SQL——两处口径一旦漂移，
     * 详情页就会显示与列表不一致的金额或分类。
     */
    fun observeEntry(journalId: String): Flow<LedgerRow?> =
        database.journalDao().observeLedgerRows().map { rows -> rows.find { it.journalId == journalId } }

    /** 本位币，null 表示科目表还没建好。 */
    fun observeBaseCurrency(): Flow<String?> = database.accountDao().observeBaseCurrency()

    /** 按会计类型取科目，用于录入表单里的下拉选项。 */
    suspend fun accountsOfTypes(types: List<AccountType>): List<AccountEntity> =
        database.accountDao().findByTypes(types)

    /** 某个会计类型在日期区间内的净额，用于月度收支合计。 */
    fun observeTotal(type: AccountType, currency: String, from: java.time.LocalDate, to: java.time.LocalDate): Flow<Long> =
        database.postingDao().observeTotal(type, currency, from.toEpochDay(), to.toEpochDay())

    /**
     * 分类合计（账目投影）。
     *
     * **聚合在 SQL 里做**，不要在界面层把整本账目拉进内存再筛。账本只会越长越大，
     * 而 `observeEntries()` 每次变动都会重发全表；报表这种「只需要合计」的视图
     * 没有理由为此付出全表装载的代价。
     */
    fun observeCategoryTotals(type: AccountType, from: java.time.LocalDate, to: java.time.LocalDate): Flow<List<CategoryTotal>> =
        database.postingDao().observeCategoryTotals(type, from.toEpochDay(), to.toEpochDay())

    /**
     * 按月分组的收支合计（账目投影）。
     *
     * 一次查全部月份，而不是每个月各查一次——后者是 O(月数 × 全表)。
     */
    fun observeMonthlyTotals(from: java.time.LocalDate, to: java.time.LocalDate): Flow<List<MonthlyTotal>> =
        database.postingDao().observeMonthlyTotals(from.toEpochDay(), to.toEpochDay())

    /**
     * 某月的科目余额试算：每科目的期初净额 + 本期借贷合计。
     *
     * **聚合在 SQL 里做**，全方向纳入 `posting`（不止收支侧）——
     * `observeEntries()` 只投影收支那一侧，算不了资产/负债余额。
     * 日期窗口由 [MonthlyReportQuery.window] 算（含闰年二月、12 月翻年）。
     */
    fun observeAccountBalances(yearMonth: java.time.YearMonth): Flow<List<AccountPeriodBalance>> {
        val window = MonthlyReportQuery.window(yearMonth)
        return database.postingDao()
            .observeAccountPeriodBalances(window.fromEpochDay, window.toEpochDay)
    }

    /**
     * 某月的凭证明细行：该月每张凭证的全部分录（借贷双方）。
     *
     * 一张凭证多条分录返回多行，消费方按 [VoucherLine.journalId] 分组。
     * 过滤在 SQL 里做，不拉全表再筛。
     */
    fun observeVoucherLines(yearMonth: java.time.YearMonth): Flow<List<VoucherLine>> {
        val window = MonthlyReportQuery.window(yearMonth)
        return database.journalDao().observeVoucherLines(window.fromEpochDay, window.toEpochDay)
    }

    /**
     * 某个月的报表聚合：收入、支出、支出侧分类合计、上月支出。
     *
     * **一次问出一个月要的全部数**，把「哪个月对应哪段日期」这个决定收在数据层——
     * 日期窗口（含闰年二月、12 月翻年）由 [MonthlyReportQuery] 算，界面不再自己拼
     * `atDay(1)`/`atEndOfMonth()`，也就没有两处各拼一份、其中一处拼错的机会。
     *
     * 四个查询都在 SQL 里聚合（`PostingDao` 已排除 `VOID` 凭证），
     * **不把整本账目拉进内存再筛**：账本只会越长越大，而报表一次只看一个月。
     *
     * 科目表还没建好（本位币未知）时，直接发一个零值聚合、**不去跑那四个查询**：
     * 没有币种连 `Money` 都构造不出来，查了也用不上。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonthlyReport(yearMonth: java.time.YearMonth): Flow<MonthAggregate> {
        val window = MonthlyReportQuery.window(yearMonth)
        val previous = MonthlyReportQuery.previousWindow(yearMonth)
        return observeBaseCurrency().flatMapLatest { currency ->
            if (currency.isNullOrBlank()) {
                flowOf(MonthlyReportQuery.assemble(yearMonth, null, 0L, 0L, emptyList(), 0L))
            } else {
                combine(
                    database.postingDao().observeTotal(
                        AccountType.INCOME, currency, window.fromEpochDay, window.toEpochDay,
                    ),
                    database.postingDao().observeTotal(
                        AccountType.EXPENSE, currency, window.fromEpochDay, window.toEpochDay,
                    ),
                    database.postingDao().observeCategoryTotals(
                        AccountType.EXPENSE, window.fromEpochDay, window.toEpochDay,
                    ),
                    database.postingDao().observeTotal(
                        AccountType.EXPENSE, currency, previous.fromEpochDay, previous.toEpochDay,
                    ),
                ) { income, expense, categories, previousExpense ->
                    MonthlyReportQuery.assemble(
                        yearMonth = yearMonth,
                        currency = currency,
                        incomeTotal = income,
                        expenseTotal = expense,
                        categories = categories,
                        previousExpenseTotal = previousExpense,
                    )
                }
            }
        }
    }

    /** 某分类在区间内已花的金额（预算用）。没有记录时是 0，不是 null。 */
    fun observeCategorySpent(
        categoryId: String,
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): Flow<Long> = database.postingDao()
        .observeCategorySpent(categoryId, from.toEpochDay(), to.toEpochDay())

    // ---------------------------------------------------------------- 预算

    /**
     * 全部已设预算。按分类 id 排序，界面的顺序才是稳定的。
     *
     * 预算与账本存在同一张库里，所以它跟着账本一起迁移、一起备份、一起进事务——
     * 这正是它从 prefs 搬过来的理由。
     */
    fun observeBudgets(): Flow<List<CategoryBudget>> =
        database.budgetDao().observeAll().map { rows ->
            rows.map { CategoryBudget(it.categoryId, it.amountMinor) }
        }

    /**
     * 写某分类的预算。**金额非正表示清除**这条预算——一个入口就够，不必开两个。
     *
     * 清除走 DELETE 而不是写一个 0：留着一行 0 会让界面显示一条点不动的预算，
     * 而 [CategoryBudget] 的消费方也判不出「没设」与「设成 0」的区别。
     */
    suspend fun setBudget(categoryId: String, amountMinor: Long) {
        if (categoryId.isBlank()) return
        if (amountMinor > 0L) {
            database.budgetDao().upsert(BudgetEntity(categoryId, amountMinor, clock.nowMillis()))
        } else {
            database.budgetDao().deleteByCategory(categoryId)
        }
    }

    /**
     * 落一笔账。凭证与分录在同一个事务里写入，
     * 不会出现"凭证写进去了但分录没写"这种金额为零的孤儿记录。
     */
    suspend fun post(draft: JournalDraft): String {
        val journalId = ids.newId()
        val now = clock.nowMillis()
        database.withTransaction {
            insertDraft(journalId, draft, now)
        }
        return journalId
    }

    /**
     * 冲减一笔已入账的凭证（退款、撤销、冲正）。
     *
     * **两件事必须在同一个事务里**：写反向凭证 + 把原凭证的外部状态推到最新。
     * 只写冲减不更新状态，下次导入比对 `externalStatus` 会认为「又变了」，就冲第二次。
     *
     * 而且这里还会**拦住已经冲过的**：同一笔被冲两次就是账目凭空少一整笔金额，
     * 比漏一笔更难发现（数字看着还挺合理）。宁可抛异常让人看见。
     *
     * @param originalExternalStatus 原凭证在平台侧的最新状态；null 表示不动它
     *   （手记的账没有外部状态，冲减它不该凭空造一个出来）。
     */
    suspend fun postReversal(
        draft: JournalDraft,
        originalExternalStatus: String? = null,
    ): String {
        val originalId = requireNotNull(draft.reversesJournalId) {
            "postReversal 只收冲减凭证——它必须带 reversesJournalId，否则无从知道冲的是哪一笔"
        }
        val journalId = ids.newId()
        val now = clock.nowMillis()
        database.withTransaction {
            require(database.journalDao().findReversalOf(originalId) == null) {
                "凭证 $originalId 已经被冲减过了，不再冲第二次"
            }
            insertDraft(journalId, draft, now)
            if (originalExternalStatus != null) {
                database.journalDao().updateExternalStatus(originalId, originalExternalStatus, now)
            }
        }
        return journalId
    }

    private suspend fun insertDraft(journalId: String, draft: JournalDraft, now: Long) {
        database.journalDao().insert(
            JournalEntity(
                id = journalId,
                dateEpochDay = draft.dateEpochDay,
                payee = draft.payee,
                note = draft.note,
                status = draft.status,
                source = draft.source,
                externalSource = draft.externalSource,
                externalRef = draft.externalRef,
                externalStatus = draft.externalStatus,
                place = draft.place,
                reversesJournalId = draft.reversesJournalId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.postingDao().insertAll(
            draft.postings.map { posting ->
                PostingEntity(
                    id = ids.newId(),
                    journalId = journalId,
                    accountId = posting.accountId,
                    amountMinor = posting.amount.amountMinor,
                    currency = posting.amount.currency,
                )
            },
        )
        // 明细与凭证在同一个事务里写：半截的明细比没有明细更糟，
        // 界面会以为「这笔有明细」然后显示一片空白
        if (draft.items.isNotEmpty()) {
            database.journalItemDao().insertAll(
                draft.items.mapIndexed { index, item ->
                    JournalItemEntity(
                        id = ids.newId(),
                        journalId = journalId,
                        description = item.description,
                        amountMinor = item.amountMinor,
                        sortOrder = index,
                    )
                },
            )
        }
    }

    /** 一笔凭证的分录。冲减要照着原凭证反向，得先把原分录读出来。 */
    suspend fun postingsOf(journalId: String): List<PostingEntity> =
        database.postingDao().findByJournal(journalId)

    /**
     * 把一笔已入账的凭证读回成草稿。
     *
     * 冲减要照原凭证反向，所以得先把它的分录读回来；顺带由 [JournalDraft] 的构造校验
     * 再确认一遍库里的这笔账是平的——库里若有坏账，冲减会**当场拒绝而不是把坏账放大一倍**。
     * 读不出来（凭证不存在，或库里的分录已经不平）时返回 null。
     */
    suspend fun draftOf(journalId: String): JournalDraft? {
        val journal = database.journalDao().findById(journalId) ?: return null
        val postings = database.postingDao().findByJournal(journalId)
        return runCatching {
            JournalDraft(
                dateEpochDay = journal.dateEpochDay,
                payee = journal.payee,
                note = journal.note,
                source = journal.source,
                postings = postings.map {
                    PostingDraft(it.accountId, Money.of(it.amountMinor, it.currency))
                },
                status = journal.status,
                place = journal.place,
                externalSource = journal.externalSource,
                externalRef = journal.externalRef,
                externalStatus = journal.externalStatus,
                reversesJournalId = journal.reversesJournalId,
            )
        }.getOrNull()
    }

    /** 一笔凭证冲减过没有。冲减的幂等依据，导入前先问一遍。 */
    suspend fun isReversed(journalId: String): Boolean =
        database.journalDao().findReversalOf(journalId) != null

    /** 按 id 取账户。冲减要认出原凭证里哪一条分录是收支分类。 */
    suspend fun accountOf(id: String): AccountEntity? = database.accountDao().findById(id)

    // ---------------------------------------------------------------- 去重

    /**
     * 这条外部流水之前导入过没有。
     *
     * ⚠️ **返回非 null 不等于「跳过」。** 同一笔在两次导出里内容会变——
     * 退款后原行的状态会从「支付成功」变成「已全额退款」。
     * 所以调用方应当**比对 [JournalEntity.externalStatus] 有没有变**：
     * 变了就要处理（生成一笔冲减、或更新状态），没变才跳过。
     *
     * 无脑跳过会**漏掉退款**，那是账目虚高一整笔的错误。
     *
     * （用查询而不是靠唯一索引抛异常，是因为异常分不清「重复导入」和「真的写坏了」。）
     */
    suspend fun findImported(source: String, externalRef: String): JournalEntity? =
        database.journalDao().findByExternalRef(source, externalRef)

    /**
     * 找可能与这笔重复的既有账目。**返回候选，不替你决定。**
     *
     * [categorySideAmount] 用分类一侧的分录口径：**支出为正、收入为负**，
     * 与账目列表显示的口径一致。
     *
     * 只比金额与时间窗，不比对商户——最需要去重的场景恰恰是「拍票时商户名不规范、
     * 导入流水后才对上」，拿商户当条件会把该匹配的漏掉。
     */
    suspend fun findDuplicateCandidates(
        categorySideAmount: Money,
        onDate: LocalDate,
        windowDays: Int = DEFAULT_DEDUPE_WINDOW_DAYS,
    ): List<DuplicateCandidate> = database.journalDao().findDuplicateCandidates(
        amountMinor = categorySideAmount.amountMinor,
        currency = categorySideAmount.currency,
        anchorEpochDay = onDate.toEpochDay(),
        windowDays = windowDays,
    )

    /** 一笔账的明细行（「买了什么」）。没有明细时返回空列表。 */
    suspend fun itemsOf(journalId: String): List<JournalItemEntity> =
        database.journalItemDao().findByJournal(journalId)

    /**
     * 这个商户以前被归到哪个分类——**用用户自己的历史，不用模型**。
     *
     * 导入流水时流水里没有分类，而让模型逐条分类既贵又慢。用户其实早就用行为
     * 回答过：他以前把「星巴克咖啡」归到餐饮，这次也该是餐饮。
     */
    suspend fun categoryForMerchant(payee: String): String? =
        database.journalDao().mostUsedCategoryFor(payee)

    suspend fun accountName(id: String): String? = database.accountDao().nameOf(id)

    /**
     * 把若干凭证的分类改到另一个分类头上（陌生商户批量归类）。
     *
     * 导入时这些行没有历史可依，落在了兜底分类（「其他支出」）；用户确认归类之后，
     * 要把它们真正挪过去。**同时也是「学一遍」**：`categoryForMerchant` 读的就是
     * 历史分录，改完之后下次导入同一个商户就会被认出来，不需要另建一张映射表——
     * 多一张表就多一处要和账本同步的状态。
     *
     * 只改分类那一侧的分录，金额与资产侧一分不动，凭证仍然平。
     * 目标账户不存在、或不是收支分类时**拒绝**：把分录挂到资产账户上，
     * 这笔账会从收支报表里静默消失，那比报错难发现得多。
     *
     * @return 真正改到的分录条数。一笔都没改到（凭证不存在、或本来就没有分类分录）
     *   时返回 0，调用方据此如实说，而不是把「没改到」当成「改好了」。
     */
    suspend fun recategorize(journalIds: List<String>, categoryAccountId: String): Int {
        if (journalIds.isEmpty()) return 0
        val target = requireNotNull(database.accountDao().findById(categoryAccountId)) {
            "分类账户不存在：$categoryAccountId"
        }
        require(target.type == AccountType.EXPENSE || target.type == AccountType.INCOME) {
            "只能改挂到收支分类上，${target.name} 是 ${target.type}"
        }

        val now = clock.nowMillis()
        return database.withTransaction {
            val changed = database.postingDao().reassignCategory(journalIds, categoryAccountId)
            if (changed > 0) database.journalDao().touch(journalIds, now)
            changed
        }
    }

    companion object {
        /**
         * 去重的时间窗，前后各 3 天。
         *
         * 不给 0 是因为三种渠道的时间本来就对不齐：小票上是消费时间、
         * 银行流水是入账时间、用户手记可能是想起来的时间，跨天很常见。
         */
        const val DEFAULT_DEDUPE_WINDOW_DAYS = 3
    }
}
