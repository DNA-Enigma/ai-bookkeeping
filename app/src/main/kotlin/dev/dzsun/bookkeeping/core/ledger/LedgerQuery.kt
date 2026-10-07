package dev.dzsun.bookkeeping.core.ledger

import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.core.money.Money
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** 问账要看账本的哪一侧。 */
enum class QueryDirection {
    /** 只看支出。 */
    EXPENSE,

    /** 只看收入。 */
    INCOME,

    /** 两侧一起，结果是**净支出**。 */
    BOTH,
}

/** 按什么把结果拆开。 */
enum class QueryGrouping {
    /** 不拆，只给一个总数。 */
    NONE,
    CATEGORY,
    MERCHANT,
    /** 按月，key 形如 `2026-10`。 */
    MONTH,
}

/**
 * 一次问账查询。**这是「问什么」与「怎么查」之间的那一层**——
 * 自然语言由调度层翻成它，本地 SQL 由 [LedgerQueryRunner] 照它执行。
 *
 * 两端日期都可空：`null` 表示这一端不设限（「一共花了多少」）。
 * 用 **epoch day** 而不是时间戳，与 `journal.dateEpochDay` 同一口径——
 * 记账的日期是日历概念，存时间戳会因时区在不同设备上显示成不同的日子。
 */
data class LedgerQuery(
    val fromEpochDay: Long? = null,
    val toEpochDay: Long? = null,
    val direction: QueryDirection = QueryDirection.EXPENSE,
    /** 分类名，**精确匹配**（分类是数据，id 会随科目表重建而变，名字才是用户说的那个词）。 */
    val categoryName: String? = null,
    /** 商户，**包含匹配**（「星巴克」要能命中「星巴克咖啡（国贸店）」）。 */
    val merchant: String? = null,
    val grouping: QueryGrouping = QueryGrouping.NONE,
    /** 分组最多返回几行。只影响 [LedgerQueryResult.groups]，不影响合计。 */
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(fromEpochDay == null || toEpochDay == null || fromEpochDay <= toEpochDay) {
            "起始日期不能晚于结束日期：$fromEpochDay > $toEpochDay"
        }
        require(limit > 0) { "分组上限必须为正，实际 $limit" }
        require(categoryName == null || categoryName.isNotBlank()) {
            "分类名要么不给，要么给一个真的名字——空串会匹配不到任何分类，看起来像「这个月没花钱」"
        }
        require(merchant == null || merchant.isNotBlank()) {
            "商户名要么不给，要么给一个真的名字——空串的 LIKE '%%' 会匹配全部，" +
                "等于把「星巴克花了几次」静默答成「这个月花了多少」"
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

/** 分组后的一行。[key] 的含义随 [LedgerQuery.grouping] 而变。 */
data class LedgerGroup(
    val key: String,
    val amount: Money,
    val entryCount: Int,
)

/**
 * 一次问账的答案。
 *
 * [total] 的方向随 [LedgerQuery.direction]：支出查询里是花掉的钱、收入查询里是挣到的钱、
 * 两侧一起时是**净支出**。三种都取正数表达「那个方向上的量」，
 * 否则同一个字段一会儿正一会儿负，读的人每次都要先想一遍符号。
 */
data class LedgerQueryResult(
    val total: Money,
    /** 凭证笔数，不是分录条数——「去了几次」问的是几趟。 */
    val entryCount: Int,
    /** 分组明细，按金额倒序；[LedgerQuery.grouping] 为 NONE 时为空。 */
    val groups: List<LedgerGroup>,
)

/**
 * 在本地执行一次问账。
 *
 * **为什么查询不并进 [LedgerRepository]**：那边是「账本的读写入口」，
 * 它的方法都在描述一次记账动作（落一笔、冲一笔、找重复）。问账是另一种东西——
 * 它读的是投影、算的是聚合，与写账的路径没有一处共用。
 * 分成两个类之后，改聚合查询不会碰到写账的那条链。
 *
 * **账本不出设备**：调度层那边跑的是它自己的 `LedgerPort`（参考实现是内存的，
 * 实测恒返回 `{"entries": [], "count": 0}`），手机上的账目它从来就看不到。
 * 所以问账的算术只能在本地做，调度层负责的是**把问题翻译成 [LedgerQuery]**，
 * 不是回答它。
 */
@Singleton
class LedgerQueryRunner @Inject constructor(
    private val database: LedgerDatabase,
) {

    /**
     * 跑一次查询。**科目表还没建好时返回 null**——那时币种未知，
     * 连 [Money] 都构造不出来。这与 `MonthAggregate.currency == null`
     * 是同一个语义：不是「这个月没花钱」，是「还算不出来」。
     */
    suspend fun run(query: LedgerQuery): LedgerQueryResult? {
        val currency = database.accountDao().observeBaseCurrency().first()?.takeIf { it.isNotBlank() }
            ?: return null

        val types = query.direction.accountTypes
        val pattern = query.merchant?.let(::likePattern)
        val dao = database.ledgerQueryDao()

        val summary = dao.summary(
            types = types,
            currency = currency,
            fromEpochDay = query.fromEpochDay,
            toEpochDay = query.toEpochDay,
            categoryName = query.categoryName,
            merchantPattern = pattern,
        )

        val groups = if (query.grouping == QueryGrouping.NONE) {
            emptyList()
        } else {
            dao.grouped(
                types = types,
                currency = currency,
                fromEpochDay = query.fromEpochDay,
                toEpochDay = query.toEpochDay,
                categoryName = query.categoryName,
                merchantPattern = pattern,
                grouping = query.grouping.name,
                unlabelled = UNLABELLED,
                limit = query.limit,
            ).map { row ->
                LedgerGroup(
                    key = row.groupKey,
                    amount = Money.of(applyDirection(row.amountMinor, query.direction), currency),
                    entryCount = row.entryCount,
                )
            }
        }

        return LedgerQueryResult(
            total = Money.of(applyDirection(summary.totalMinor, query.direction), currency),
            entryCount = summary.entryCount,
            groups = groups,
        )
    }

    private companion object {
        /** 商户名为空的流水归到这一档。空字符串当分类名显示出来会是一片空白。 */
        const val UNLABELLED = "（未填写）"
    }
}

/** 查询方向 → SQL 要筛的会计类型。转账两侧都是 ASSET，天然不在这里面。 */
internal val QueryDirection.accountTypes: List<AccountType>
    get() = when (this) {
        QueryDirection.EXPENSE -> listOf(AccountType.EXPENSE)
        QueryDirection.INCOME -> listOf(AccountType.INCOME)
        QueryDirection.BOTH -> listOf(AccountType.EXPENSE, AccountType.INCOME)
    }

/**
 * 把 SQL 侧的原始求和折算成「该方向上的量」。
 *
 * 分录的符号约定是账本的地基（`JournalDraft.expense/income`）：支出分类那一侧记正、
 * **收入分类那一侧记负**。所以收入查询要把负号翻回来，否则「这个月挣了多少」
 * 会答成一个负数。两侧一起时不翻——支出为正、收入为负相加，得到的正是净支出。
 */
internal fun applyDirection(rawMinor: Long, direction: QueryDirection): Long = when (direction) {
    QueryDirection.EXPENSE -> rawMinor
    QueryDirection.INCOME -> -rawMinor
    QueryDirection.BOTH -> rawMinor
}

/**
 * 商户名的包含匹配模式。
 *
 * `%` 与 `_` 在 LIKE 里是通配符，用户（或模型）给的商户名里若有这两个字符，
 * 不转义就会把「50%折扣店」变成「50 后面随便什么」，静默多算一堆别家的账。
 * 反斜杠自身要先转，否则 `\%` 会被拆成 `\` + 通配符。转义符在 SQL 里用 `ESCAPE '\'` 声明。
 */
internal fun likePattern(raw: String): String {
    val escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}
