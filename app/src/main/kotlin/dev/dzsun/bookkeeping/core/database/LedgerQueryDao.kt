package dev.dzsun.bookkeeping.core.database

import androidx.room.Dao
import androidx.room.Query

/**
 * 问账的聚合查询。
 *
 * 全部 `SUM` / `COUNT` / `GROUP BY` 都在 SQL 里做，**不把整本账目拉进内存再筛**：
 * 问账是「读一次就给个答案」，没有理由为它装载全表。
 *
 * 参数一律可空，`NULL` 即「这一维不过滤」——`(:x IS NULL OR ...)` 这个写法让
 * 七个可选条件收敛成一条语句，而不是 2^7 个变体。Room 在编译期校验这些 SQL，
 * 打错列名的代价是构建失败而不是运行时静默返回零。
 *
 * 三条口径与账本其余部分一致，都在 SQL 里守住：
 * - `status != 'VOID'`：作废的账不进任何统计；
 * - `a.type IN (...)`：只取收支分类那一侧的分录，**转账天然不参与**
 *   （它两侧都是 ASSET，落不进这个条件）；
 * - `p.currency = :currency`：**不跨币种求和**。把 ¥ 和 $ 加在一起得到的数
 *   既不是人民币也不是美元，而它看起来像个正常金额，不会有人发现。
 */
@Dao
interface LedgerQueryDao {

    /**
     * 区间合计与笔数。
     *
     * [LedgerSummaryRow.entryCount] 数的是**凭证**（`COUNT(DISTINCT j.id)`）而不是分录：
     * 用户问「星巴克去了几次」，问的是去了几趟，不是账上有几条分录。
     */
    @Query(
        """
        SELECT COALESCE(SUM(p.amountMinor), 0) AS totalMinor,
               COUNT(DISTINCT j.id) AS entryCount
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        WHERE a.type IN (:types)
          AND p.currency = :currency
          AND j.status != 'VOID'
          AND (:fromEpochDay IS NULL OR j.dateEpochDay >= :fromEpochDay)
          AND (:toEpochDay IS NULL OR j.dateEpochDay <= :toEpochDay)
          AND (:categoryName IS NULL OR a.name = :categoryName)
          AND (:merchantPattern IS NULL OR j.payee LIKE :merchantPattern ESCAPE '\')
        """,
    )
    suspend fun summary(
        types: List<AccountType>,
        currency: String,
        fromEpochDay: Long?,
        toEpochDay: Long?,
        categoryName: String?,
        merchantPattern: String?,
    ): LedgerSummaryRow

    /**
     * 分组明细。`groupKey` 由 `:grouping` 选出的表达式算出——
     * 一个参数切换四种分组，而不是把同一段 WHERE 抄四遍（抄四遍就会有四处走样）。
     *
     * `GROUP BY` 用输出别名 `groupKey`：SQLite 明确允许按别名分组，
     * 而重复整段 `CASE` 表达式只是把同一个式子写两遍，反而更容易写岔。
     */
    @Query(
        """
        SELECT CASE :grouping
                 WHEN 'CATEGORY' THEN a.name
                 WHEN 'MERCHANT' THEN COALESCE(NULLIF(j.payee, ''), :unlabelled)
                 WHEN 'MONTH'    THEN substr(date(j.dateEpochDay * 86400, 'unixepoch'), 1, 7)
                 ELSE :unlabelled
               END AS groupKey,
               SUM(p.amountMinor) AS amountMinor,
               COUNT(DISTINCT j.id) AS entryCount
        FROM journal j
        JOIN posting p ON p.journalId = j.id
        JOIN account a ON a.id = p.accountId
        WHERE a.type IN (:types)
          AND p.currency = :currency
          AND j.status != 'VOID'
          AND (:fromEpochDay IS NULL OR j.dateEpochDay >= :fromEpochDay)
          AND (:toEpochDay IS NULL OR j.dateEpochDay <= :toEpochDay)
          AND (:categoryName IS NULL OR a.name = :categoryName)
          AND (:merchantPattern IS NULL OR j.payee LIKE :merchantPattern ESCAPE '\')
        GROUP BY groupKey
        ORDER BY amountMinor DESC
        LIMIT :limit
        """,
    )
    suspend fun grouped(
        types: List<AccountType>,
        currency: String,
        fromEpochDay: Long?,
        toEpochDay: Long?,
        categoryName: String?,
        merchantPattern: String?,
        grouping: String,
        unlabelled: String,
        limit: Int,
    ): List<LedgerGroupRow>
}

/** 区间合计与笔数。没有匹配行时 [totalMinor] 为 0、[entryCount] 为 0（`COALESCE` 保证不为 null）。 */
data class LedgerSummaryRow(
    val totalMinor: Long,
    val entryCount: Int,
)

/**
 * 分组后的一行。
 *
 * [groupKey] 的含义随分组维度而变：分类名 / 商户名 / `2026-10` 这样的月份。
 * 用什么维度分组由调用方记着，这里不重复存一份——两份就会不一致。
 */
data class LedgerGroupRow(
    val groupKey: String,
    val amountMinor: Long,
    val entryCount: Int,
)
