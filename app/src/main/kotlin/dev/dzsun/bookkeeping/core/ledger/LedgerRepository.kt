package dev.dzsun.bookkeeping.core.ledger

import androidx.room.withTransaction
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.CategoryTotal
import dev.dzsun.bookkeeping.core.database.MonthlyTotal
import dev.dzsun.bookkeeping.core.database.JournalEntity
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.database.PostingEntity
import dev.dzsun.bookkeeping.core.platform.Clock
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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
                    status = JournalStatus.CLEARED,
                    source = draft.source,
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
        }
        return journalId
    }
}
