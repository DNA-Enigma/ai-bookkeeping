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

/**
 * 这笔账从哪来。**按渠道分，不按"是不是 AI"分。**
 *
 * 渠道决定三件事：合并时谁的数据更可信（流水的金额与日期权威，票的明细与商户权威）、
 * 界面上怎么向用户交代这笔的来源、以及去重要不要看外部流水号。
 * 所以 `AI_IMPORT` 这种说法不够用——它只说"AI 干的"，没说清是从图里还是从话里。
 */
enum class JournalSource {
    /** 用户手工录入 */
    MANUAL,

    /** 拍票据 / 截图后由 AI 抽取 */
    RECEIPT,

    /** 语音说一句后由 AI 抽取 */
    VOICE,

    /** 从支付宝 / 微信 / 银行流水文件导入 */
    STATEMENT,

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
    indices = [
        Index("dateEpochDay"),
        Index("status"),
        // 去重键：同一份流水重复导入、或同一笔又拍票又导入，都靠它拦。
        // SQLite 的唯一索引把 NULL 当作互不相同，所以手记/拍票那些
        // externalRef 为 null 的行不受影响。
        Index(value = ["externalSource", "externalRef"], unique = true),
        // 「这笔被冲减过没有」每次导入都要问一遍（冲减的幂等依据）
        Index("reversesJournalId"),
    ],
)
data class JournalEntity(
    @PrimaryKey val id: String,
    val dateEpochDay: Long,
    val payee: String? = null,
    val note: String? = null,
    val status: JournalStatus,
    val source: JournalSource,

    /** 外部流水的来源标识，如 `alipay` / `wechat` / `cmb`。 */
    val externalSource: String? = null,

    /**
     * 该来源里**唯一标识这一笔**的单号。
     *
     * 取微信/支付宝的「**交易单号**」（平台侧生成、全局唯一），
     * **不要取「商户单号」**——那是商户自己的订单号，跨商户会撞号，且常为空。
     *
     * 与 [externalSource] 组成去重键，只保证「同一份文件重复导入不重复」。
     * **跨渠道的同一笔解不了**（同一笔消费在微信与银行里是不同单号），那要靠模糊匹配。
     */
    val externalRef: String? = null,

    /**
     * 上次导入时该笔在流水里的**平台侧状态**（如「已全额退款」「交易关闭」）。
     *
     * 存在的理由：**同一笔在两次导出里内容会变**——退款后原行的状态会从「支付成功」
     * 变成「已全额退款」。所以导入不能「有则跳过」，得比对状态；要能比对，
     * 就得留住上次见到的那个值。
     *
     * 与 [JournalStatus] 无关：那个是**我们的**核对状态（待确认/已核对/作废），
     * 这个是**对方的**原始状态，不要混用。
     */
    val externalStatus: String? = null,

    /**
     * 发生在哪。**这是回想时的锚点**——几周后「这笔是什么」往往靠地点想起来，
     * 金额和商户都记不住的时候，地点还在。可为空。
     */
    val place: String? = null,

    /**
     * 这是一笔**冲减**（退款/撤销/冲正），冲的是哪一笔凭证。非空即冲减。
     *
     * 冲减本身靠**负向分录**表达（见 `JournalDraft.reversalOf`），金额没有第二种记法；
     * 这个字段只负责溯源与幂等：没有它，用户先导出了新账单（状态已是「已全额退款」）、
     * 又补导一份旧账单（状态还是「交易成功」）时，会被冲减第二次。
     *
     * 不加外键：凭证作废后仍要留下冲减痕迹，让它指向一条已作废的行比拦住写入要好。
     */
    val reversesJournalId: String? = null,

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

/**
 * 一笔账的明细行。
 *
 * 存在的理由只有一个：**「这笔买了什么」的答案只在小票上**。
 * 金额、商户、分类都不足以让人几周后想起一笔支出，而小票上那行「拿铁 大杯」可以——
 * 而抽取的时候它本来就在手里，存下来几乎零成本。
 *
 * **它不参与记账。** 金额仍以 [PostingEntity] 为准，明细纯粹是描述。
 * 所以：一张小票可以没有明细；明细之和也不必等于总额——折扣、税、抹零都会让两者不等，
 * 强行对齐反而会造出错账。这也意味着**一张小票暂时不能拆成多个分类**；
 * 真要做拆分会是另一层（明细挂到分录上），现在不做，等真有需求再加。
 */
@Entity(
    tableName = "journal_item",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("journalId")],
)
data class JournalItemEntity(
    @PrimaryKey val id: String,
    val journalId: String,
    /** 小票上那行字，如「拿铁 大杯」。 */
    val description: String,
    /** 明细金额。**可为空**——很多小票只给总额。 */
    val amountMinor: Long? = null,
    val sortOrder: Int = 0,
)

/**
 * 某个分类的**月度上限**（「餐饮每月 ≤ 1100」）。
 *
 * **一个分类一行**，主键就是分类 id：预算是经常性的额度，不是逐月实例——
 * 后者要为每个月建一行，用户没设过的月份还得回退到某个默认值，
 * 平白多出一层「哪一份才算数」的含义。真要做成按月不同时，再加 `month` 列、
 * 主键改 `(categoryId, month)`，那是一次显式的迁移。
 *
 * **不加外键指向 `account`。** 分类是数据不是代码，用户删/归档分类后这条预算还该留着——
 * `CASCADE` 会**静默**把预算删掉（用户既看不到也删不掉它），`RESTRICT` 又会挡住分类操作，
 * 两者都不是想要的。界面按 id 兜底显示，让用户自己决定怎么处理。
 *
 * 金额是钱，一律整数最小单位，与账本其余部分同一口径。
 */
@Entity(tableName = "budget")
data class BudgetEntity(
    @PrimaryKey val categoryId: String,
    val amountMinor: Long,
    val updatedAt: Long,
)
