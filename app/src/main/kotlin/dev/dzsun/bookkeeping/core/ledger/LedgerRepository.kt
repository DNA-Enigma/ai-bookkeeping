package dev.dzsun.bookkeeping.core.ledger

import androidx.room.withTransaction
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.database.DuplicateCandidate
import dev.dzsun.bookkeeping.core.database.JournalEntity
import dev.dzsun.bookkeeping.core.database.JournalItemEntity
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.database.MonthlyTotal
import dev.dzsun.bookkeeping.core.database.PostingEntity
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import dev.dzsun.bookkeeping.core.platform.Clock
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
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
     * 落一笔账。凭证与分录在同一个事务里写入，
     * 不会出现"凭证写进去了但分录没写"这种金额为零的孤儿记录。
     */
    suspend fun post(draft: JournalDraft): String {
        val journalId = ids.newId()
        val now = clock.nowMillis()
        database.withTransaction {
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
        return journalId
    }

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
