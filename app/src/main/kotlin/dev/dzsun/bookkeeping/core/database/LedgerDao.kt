package dev.dzsun.bookkeeping.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 账目列表的一行。
 *
 * 由 journal ⋈ posting ⋈ account 折出，不是一张实体表——账目是流水的投影。
 * 选中 EXPENSE / INCOME 那一侧的分录，因为它才是用户眼里的"这笔花了多少、算哪类"。
 */
data class LedgerRow(
    val journalId: String,
    val dateEpochDay: Long,
    val payee: String?,
    val note: String?,
    val status: JournalStatus,
    val source: JournalSource,
    val categoryId: String,
    val categoryName: String,
    val categoryType: AccountType,
    val counterpartyName: String,
    val amountMinor: Long,
    val currency: String,
)

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    @Query("SELECT * FROM account WHERE type IN (:types) AND archived = 0 ORDER BY sortOrder, name")
    fun observeByTypes(types: List<AccountType>): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE type IN (:types) AND archived = 0 ORDER BY sortOrder, name")
    suspend fun findByTypes(types: List<AccountType>): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    /**
     * 本位币。科目表建好之后它就不会再变，界面上所有合计都用它——
     * 界面不该把 "CNY" 写死，否则换一套科目表就得改代码。
     */
    @Query("SELECT currency FROM account ORDER BY sortOrder LIMIT 1")
    fun observeBaseCurrency(): Flow<String?>
}

@Dao
interface JournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(journal: JournalEntity)

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun findById(id: String): JournalEntity?

    @Query("DELETE FROM journal WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 账目投影。按日期倒序；同一天内新录入的在前，因为用户刚记完就想看到它。
     */
    @Query(
        """
        SELECT j.id AS journalId,
               j.dateEpochDay AS dateEpochDay,
               j.payee AS payee,
               j.note AS note,
               j.status AS status,
               j.source AS source,
               p.accountId AS categoryId,
               a.name AS categoryName,
               a.type AS categoryType,
               COALESCE(c.name, '') AS counterpartyName,
               p.amountMinor AS amountMinor,
               p.currency AS currency
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        LEFT JOIN posting cp ON cp.journalId = j.id AND cp.accountId != p.accountId
        LEFT JOIN account c ON c.id = cp.accountId
        WHERE a.type IN ('EXPENSE', 'INCOME')
        ORDER BY j.dateEpochDay DESC, j.createdAt DESC
        """,
    )
    fun observeLedgerRows(): Flow<List<LedgerRow>>
}

@Dao
interface PostingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(postings: List<PostingEntity>)

    @Query("SELECT * FROM posting WHERE journalId = :journalId")
    suspend fun findByJournal(journalId: String): List<PostingEntity>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM posting WHERE journalId = :journalId")
    suspend fun sumOfJournal(journalId: String): Long

    /**
     * 某类账户在日期区间内的净额。支出是正数、收入也是正数，方向由 [type] 区分。
     * 转账不参与——它两侧都是 ASSET，不会落进这个查询。
     */
    @Query(
        """
        SELECT COALESCE(SUM(p.amountMinor), 0)
        FROM posting p
        JOIN account a ON a.id = p.accountId
        JOIN journal j ON j.id = p.journalId
        WHERE a.type = :type
          AND p.currency = :currency
          AND j.status != 'VOID'
          AND j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        """,
    )
    fun observeTotal(type: AccountType, currency: String, fromEpochDay: Long, toEpochDay: Long): Flow<Long>
}
