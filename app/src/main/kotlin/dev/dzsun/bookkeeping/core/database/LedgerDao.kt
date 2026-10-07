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
    /** 发生地点。详情页与列表副标题用它当「几周后想起来」的锚点。 */
    val place: String? = null,
    /**
     * 非空即这是一笔**冲减**，值是它冲的那笔凭证。
     *
     * 界面靠它把「退款」和「一笔负数的支出」分开说——两者金额都不好看，
     * 但前者用户能理解，后者用户会以为记错了。
     */
    val reversesJournalId: String? = null,
)

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    @Query("SELECT name FROM account WHERE id = :id")
    suspend fun nameOf(id: String): String?

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
               j.place AS place,
               j.reversesJournalId AS reversesJournalId,
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

    /**
     * 某月的凭证明细：该月每张 `journal` 的**全部** `posting`（借贷双方都要，
     * 凭证要展示分录对），按日期倒序；同一天新录入的在前，与 [observeLedgerRows] 一致。
     *
     * 一张凭证多条分录会返回多行，消费方按 [VoucherLine.journalId] 分组。
     * 这里按 `p.amountMinor` 把借方（正）排在贷方（负）前面，分组后直接顺序渲染即可。
     */
    @Query(
        """
        SELECT j.id AS journalId,
               j.dateEpochDay AS dateEpochDay,
               j.payee AS payee,
               j.note AS note,
               j.status AS status,
               j.source AS source,
               j.place AS place,
               p.id AS postingId,
               p.accountId AS accountId,
               a.name AS accountName,
               a.type AS accountType,
               p.amountMinor AS amountMinor,
               p.currency AS currency
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        WHERE j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
          AND j.status != 'VOID'
        ORDER BY j.dateEpochDay DESC, j.createdAt DESC,
                 CASE WHEN p.amountMinor > 0 THEN 0 ELSE 1 END
        """,
    )
    fun observeVoucherLines(fromEpochDay: Long, toEpochDay: Long): Flow<List<VoucherLine>>

    /**
     * 按外部流水号找已导入过的那一笔。
     *
     * 这是**重复导入同一份文件**的幂等依据：单号已存在就跳过，
     * 不必依赖唯一索引抛异常（异常没法区分「重复导入」与「真的写坏了」）。
     */
    @Query(
        """
        SELECT * FROM journal
        WHERE externalSource = :source AND externalRef = :ref
        LIMIT 1
        """,
    )
    suspend fun findByExternalRef(source: String, ref: String): JournalEntity?

    /**
     * 这笔凭证已经被冲减过没有。冲减的**幂等依据**。
     *
     * 光靠比对 `externalStatus` 是不够的：用户先导出一份新账单（状态已是
     * 「已全额退款」）冲减了一次，回头又补导一份旧账单（状态还写着「交易成功」），
     * 状态一比对又是「变了」，就会冲第二次。
     */
    @Query("SELECT * FROM journal WHERE reversesJournalId = :journalId LIMIT 1")
    suspend fun findReversalOf(journalId: String): JournalEntity?

    /**
     * 只更新平台侧的原始状态，其余一概不动。
     *
     * 冲减写完之后要把原凭证的状态推到最新，否则下次导入比对状态时又会认为「变了」。
     */
    @Query("UPDATE journal SET externalStatus = :status, updatedAt = :updatedAt WHERE id = :journalId")
    suspend fun updateExternalStatus(journalId: String, status: String?, updatedAt: Long)

    /** 改挂分类之后刷新凭证的更新时间，让账目列表的重排与缓存失效跟上。 */
    @Query("UPDATE journal SET updatedAt = :updatedAt WHERE id IN (:journalIds)")
    suspend fun touch(journalIds: List<String>, updatedAt: Long)

    /**
     * 这个商户以前被归到哪个分类——**用用户自己的历史，不用模型**。
     *
     * 导入流水时最麻烦的是流水里没有分类。让模型给几百行逐条分类既贵又慢，
     * 而用户早就用行为回答过这个问题：他以前把「星巴克咖啡」归到餐饮，
     * 这次也该是餐饮。取用得最多的那个。
     *
     * 只做**精确匹配**商户名。模糊匹配（「星巴克咖啡（国贸店）」与「（望京店）」）
     * 留待以后——先做对，再做广。
     */
    @Query(
        """
        SELECT p.accountId
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        WHERE a.type IN ('EXPENSE', 'INCOME')
          AND j.payee = :payee
          AND j.status != 'VOID'
        GROUP BY p.accountId
        ORDER BY COUNT(*) DESC
        LIMIT 1
        """,
    )
    suspend fun mostUsedCategoryFor(payee: String): String?

    /**
     * 找可能与「金额 [amountMinor]、日期 [anchorEpochDay] 前后 [windowDays] 天」重复的既有账目。
     *
     * 只比金额与时间窗，**不比对商户**——因为最需要去重的场景恰恰是
     * 「拍票时商户名不规范、导入流水后商户名才对上」，用商户做条件会把该匹配的漏掉。
     * 商户留给调用方在候选上做二次判断。
     *
     * `amountMinor` 取分类一侧的分录（支出为正、收入为负），与 [LedgerRow] 的口径一致。
     */
    @Query(
        """
        SELECT j.id AS journalId,
               j.dateEpochDay AS dateEpochDay,
               j.payee AS payee,
               j.source AS source,
               p.amountMinor AS amountMinor,
               p.currency AS currency,
               a.name AS categoryName,
               ABS(j.dateEpochDay - :anchorEpochDay) AS dayDistance
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        WHERE a.type IN ('EXPENSE', 'INCOME')
          AND p.amountMinor = :amountMinor
          AND p.currency = :currency
          AND j.status != 'VOID'
          AND j.dateEpochDay BETWEEN :anchorEpochDay - :windowDays AND :anchorEpochDay + :windowDays
        ORDER BY dayDistance
        """,
    )
    suspend fun findDuplicateCandidates(
        amountMinor: Long,
        currency: String,
        anchorEpochDay: Long,
        windowDays: Int,
    ): List<DuplicateCandidate>
}

@Dao
interface JournalItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<JournalItemEntity>)

    @Query("SELECT * FROM journal_item WHERE journalId = :journalId ORDER BY sortOrder")
    suspend fun findByJournal(journalId: String): List<JournalItemEntity>

    @Query("SELECT * FROM journal_item WHERE journalId IN (:journalIds) ORDER BY journalId, sortOrder")
    suspend fun findByJournals(journalIds: List<String>): List<JournalItemEntity>

    @Query("DELETE FROM journal_item WHERE journalId = :journalId")
    suspend fun deleteByJournal(journalId: String)
}

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    /**
     * 清除一条预算。**「没设预算」与「预算为 0」不是一回事**，
     * 所以清除走 DELETE 而不是写一个 0——留着一行 0 会让界面显示一条点不动的预算，
     * 而 `BudgetLevel.UNSET` 也判不出来。
     */
    @Query("DELETE FROM budget WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Query("SELECT * FROM budget ORDER BY categoryId")
    fun observeAll(): Flow<List<BudgetEntity>>
}

/**
 * 某个分类在区间内的合计。**聚合在 SQL 里做**，不要把整本账目拉进内存再用 Kotlin 过滤——
 * 账本会随时间增长，而 `observeEntries()` 每次变动都会重发全表。
 */
data class CategoryTotal(
    val categoryId: String,
    val categoryName: String,
    val amountMinor: Long,
)

/**
 * 一条可能与新记录重复的既有账目。
 *
 * **去重只产出「候选」，不自动合并。** 同一天两杯一模一样的咖啡是真实存在的，
 * 自动合并会吃掉用户真实记过的账——那比漏一笔更伤信任。
 */
data class DuplicateCandidate(
    val journalId: String,
    val dateEpochDay: Long,
    val payee: String?,
    val source: JournalSource,
    val amountMinor: Long,
    val currency: String,
    val categoryName: String,
    /** 与锚点日期的天数差，用来排序：越近越可能是同一笔。 */
    val dayDistance: Long,
)

/** 某个月某个方向的合计。[yearMonth] 形如 `2026-10`。 */
data class MonthlyTotal(
    val yearMonth: String,
    val accountType: AccountType,
    val amountMinor: Long,
)

/**
 * 某个科目在某个月的余额试算行。
 *
 * **金额一律整数最小单位**，与账本其余部分同一口径。
 * [openingNetMinor] 与净额口径是**借方为正、贷方为负**——所以收入科目的期初
 * 在库里是负数，展示时按 [accountType] 的余额方向翻正。
 *
 * 与 [LedgerRow] 的关键区别：那个只投影收支一侧（`a.type IN ('EXPENSE', 'INCOME')`），
 * 算不了资产/负债侧的余额；这里把 `posting` 全方向纳入，才能做复式试算。
 */
data class AccountPeriodBalance(
    val accountId: String,
    val accountName: String,
    val accountType: AccountType,
    val sortOrder: Int,
    /** 期初净额（借方为正）。取该月首日之前的全部分录之和。 */
    val openingNetMinor: Long,
    /** 本期借方合计。 */
    val debitMinor: Long,
    /** 本期贷方合计。 */
    val creditMinor: Long,
)

/**
 * 凭证的一条分录行。借贷双方都在内——凭证要展示完整的分录对，
 * 只取一侧就看不出「有借必有贷」。
 *
 * 一张凭证有多条分录时会返回多行，消费方按 [journalId] 分组。
 * 分组在调用方做是因为这只是展示结构，不是聚合——月度过滤与连接都已经在 SQL 里完成了。
 */
data class VoucherLine(
    val journalId: String,
    val dateEpochDay: Long,
    val payee: String?,
    val note: String?,
    val status: JournalStatus,
    val source: JournalSource,
    val place: String?,
    val postingId: String,
    val accountId: String,
    val accountName: String,
    val accountType: AccountType,
    /** 借方为正、贷方为负，与 [PostingEntity.amountMinor] 同一符号约定。 */
    val amountMinor: Long,
    val currency: String,
)

@Dao
interface PostingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(postings: List<PostingEntity>)

    @Query("SELECT * FROM posting WHERE journalId = :journalId")
    suspend fun findByJournal(journalId: String): List<PostingEntity>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM posting WHERE journalId = :journalId")
    suspend fun sumOfJournal(journalId: String): Long

    /**
     * 把若干凭证的**分类分录**改挂到另一个分类账户上（陌生商户归类用）。
     *
     * 只动 `EXPENSE` / `INCOME` 那一侧：资产、负债那侧不动，于是**金额一分不变、
     * 借贷仍然平**，只是这笔账从此算在另一个分类头上。金额要变的话那是另一回事
     * （改账），不该混进「归类」这个动作里。
     *
     * 返回改动的行数——界面靠它说「改了 N 笔」，也靠它发现「一笔都没改到」
     * （参数对不上时不能默默算成功）。
     */
    @Query(
        """
        UPDATE posting SET accountId = :categoryAccountId
        WHERE journalId IN (:journalIds)
          AND accountId IN (SELECT id FROM account WHERE type IN ('EXPENSE', 'INCOME'))
        """,
    )
    suspend fun reassignCategory(journalIds: List<String>, categoryAccountId: String): Int

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

    /**
     * 分类合计，用于「钱花在哪了」的占比。
     *
     * 排除了 `VOID`——作废的账不该出现在报表里。转账自然不参与：
     * 它两侧都是 ASSET，落不进 `a.type = :type` 这个条件。
     */
    @Query(
        """
        SELECT p.accountId AS categoryId,
               a.name AS categoryName,
               SUM(p.amountMinor) AS amountMinor
        FROM posting p
        JOIN account a ON a.id = p.accountId
        JOIN journal j ON j.id = p.journalId
        WHERE a.type = :type
          AND j.status != 'VOID'
          AND j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        GROUP BY p.accountId, a.name
        ORDER BY amountMinor DESC
        """,
    )
    fun observeCategoryTotals(
        type: AccountType,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): Flow<List<CategoryTotal>>

    /**
     * **某一个分类**在区间内已花的金额。
     *
     * 与 [observeCategoryTotals] 的区别只在粒度：那个一次给全部分类（预算页/报表页要的），
     * 这个只给一个（记账后即时问一句「这笔会让餐饮超预算吗」）。
     * 拿全部再在内存里 find 也行，但那要连带把其余分类的合计也读出来，白读。
     *
     * 没有记录时返回 0 而不是 null——「这个月还没在餐饮上花过钱」是确切的事实，
     * 不是「不知道」。
     */
    @Query(
        """
        SELECT COALESCE(SUM(p.amountMinor), 0)
        FROM posting p
        JOIN journal j ON j.id = p.journalId
        WHERE p.accountId = :categoryId
          AND j.status != 'VOID'
          AND j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        """,
    )
    fun observeCategorySpent(
        categoryId: String,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): Flow<Long>

    /**
     * 按月分组的收支合计，用于趋势柱状图。
     *
     * `dateEpochDay * 86400` 再配 `'unixepoch'` 是**精确往返**的：
     * epochDay 是天数，乘回秒数落在 UTC 午夜，不会有时区偏移。
     * 一次查询出全部月份，而不是每个月各查一次。
     */
    @Query(
        """
        SELECT substr(date(j.dateEpochDay * 86400, 'unixepoch'), 1, 7) AS yearMonth,
               a.type AS accountType,
               SUM(p.amountMinor) AS amountMinor
        FROM posting p
        JOIN account a ON a.id = p.accountId
        JOIN journal j ON j.id = p.journalId
        WHERE a.type IN ('EXPENSE', 'INCOME')
          AND j.status != 'VOID'
          AND j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        GROUP BY yearMonth, a.type
        ORDER BY yearMonth
        """,
    )
    fun observeMonthlyTotals(fromEpochDay: Long, toEpochDay: Long): Flow<List<MonthlyTotal>>

    /**
     * 按科目聚合的余额试算：期初净额 + 本期借方合计 + 本期贷方合计。
     *
     * **聚合在 SQL 里做**（同上）：一次查询、按科目分组，不把整本账目拉进内存再筛。
     * 全方向纳入 `posting`（不止收支侧）——这是 [LedgerRow] 做不了的事，
     * 资产/负债侧的余额只在这张投影里有。
     *
     * 符号约定与 [PostingEntity.amountMinor] 一致：**借方为正、贷方为负**。
     * 借方合计取正分录之和，贷方合计取负分录绝对值之和，所以一张平的凭证
     * 对两个合计的贡献相等；整月下来「本期借方合计 = 本期贷方合计」。
     *
     * 只返回有分录的科目（没记过账的科目余额为零，不该在试算表上占一行）。
     * 排除 `VOID`——作废的账不该进试算。
     */
    @Query(
        """
        SELECT p.accountId AS accountId,
               a.name AS accountName,
               a.type AS accountType,
               a.sortOrder AS sortOrder,
               SUM(CASE WHEN j.dateEpochDay < :fromEpochDay THEN p.amountMinor ELSE 0 END) AS openingNetMinor,
               SUM(CASE WHEN j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay AND p.amountMinor > 0
                        THEN p.amountMinor ELSE 0 END) AS debitMinor,
               SUM(CASE WHEN j.dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay AND p.amountMinor < 0
                        THEN -p.amountMinor ELSE 0 END) AS creditMinor
        FROM posting p
        JOIN account a ON a.id = p.accountId
        JOIN journal j ON j.id = p.journalId
        WHERE j.status != 'VOID'
          AND j.dateEpochDay <= :toEpochDay
        GROUP BY p.accountId, a.name, a.type, a.sortOrder
        ORDER BY a.sortOrder, a.name
        """,
    )
    fun observeAccountPeriodBalances(fromEpochDay: Long, toEpochDay: Long): Flow<List<AccountPeriodBalance>>
}
