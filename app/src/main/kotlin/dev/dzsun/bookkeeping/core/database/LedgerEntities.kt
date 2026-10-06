package dev.dzsun.bookkeeping.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账户的会计类型。
 *
 * 分类不单独建表——**支出与收入分类本身就是 EXPENSE / INCOME 类型的账户行**。
 * 这样转账（ASSET→ASSET）、支出（ASSET→EXPENSE）、退款（EXPENSE 的负向分录）
 * 走的是同一套统计逻辑，不需要为转账写特例。
 */
enum class AccountType {
    /** 现金、银行卡、支付宝、微信、投资账户 */
    ASSET,

    /** 信用卡、花呗、房贷 */
    LIABILITY,

    /** 收入分类 */
    INCOME,

    /** 支出分类 */
    EXPENSE,

    /** 权益类，用于期初余额 */
    EQUITY,
}

/** 一笔账目的核对状态。AI 抽取的结果先进 [PENDING]，用户在确认页核对后转 [CLEARED]。 */
enum class JournalStatus {
    PENDING,
    CLEARED,
    VOID,
}

/** 这笔账从哪来。 */
enum class JournalSource {
    /** AI 从截图或文字抽取 */
    AI_IMPORT,

    /** 用户手工录入 */
    MANUAL,

    /** 定期重复记账 */
    RECURRING,

    /** 投资订单 */
    ORDER,
}

@Entity(
    tableName = "account",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId"), Index("type")],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val parentId: String? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * 一笔记账事件（会计上的"凭证"）。
 *
 * 刻意**不存金额**——金额只存在于分录上，这样一个凭证的金额含义永远由分录决定，
 * 不会出现"凭证金额与分录之和不一致"这种自相矛盾的状态。
 *
 * [dateEpochDay] 存的是"哪天"（epoch day），不是时间戳。记账的日期是日历概念，
 * 存成时间戳会因为时区在不同设备上显示成不同的日子。
 */
@Entity(
    tableName = "journal",
    indices = [Index("dateEpochDay"), Index("status")],
)
data class JournalEntity(
    @PrimaryKey val id: String,
    val dateEpochDay: Long,
    val payee: String? = null,
    val note: String? = null,
    val status: JournalStatus,
    val source: JournalSource,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 一条分录：某个账户在某个凭证里的增减。
 *
 * **不变式**：同一凭证下所有分录的 [amountMinor] 之和恒为零。
 * 支出是一笔 ASSET 的负向分录配一笔 EXPENSE 的正向分录；转账是两笔 ASSET 分录一正一负。
 * 由 LedgerRepository 在写入前强制校验。
 */
@Entity(
    tableName = "posting",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("journalId"), Index("accountId")],
)
data class PostingEntity(
    @PrimaryKey val id: String,
    val journalId: String,
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
    val note: String? = null,
)
