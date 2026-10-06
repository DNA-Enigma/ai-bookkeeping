package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.money.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一笔流水的收支方向。
 *
 * [NEUTRAL] 不是"没填"，而是账单自己声明的**第三档**：钱动了，但既不是收入也不是支出。
 * 实测支付宝写「不计收支」（596 笔里 34 笔），微信写 `/`（86 笔里 8 笔）。
 * 充值、提现、还款、理财申赎都落在这里——混进收支会让「这个月花了多少」直接失真。
 */
enum class StatementDirection { EXPENSE, INCOME, NEUTRAL }

/**
 * 从流水文件里解析出的一行，**已归一化**。
 *
 * 归一化指的是：金额已经变成整数最小单位的 [Money]、日期已经变成 epoch day、
 * 收支方向已经变成枚举。原始文本留在 [rawTime] 等处，出问题时能对回去。
 *
 * 它**还不是**账本条目——中间还隔着「是不是消费」的判断与去重。
 */
data class StatementRow(
    /** 来源标识，如 `wechat` / `alipay` / `cmb`。写进 journal.externalSource。 */
    val externalSource: String,
    /** 平台侧的交易单号。**取这个不取商户单号**——后者是商户自己的订单号，跨商户会撞号。 */
    val externalRef: String,
    val dateEpochDay: Long,
    /** 金额绝对值。方向看 [direction]。 */
    val amount: Money,
    val direction: StatementDirection,
    val merchant: String?,
    /** 商品说明，如「拿铁 大杯」。这是「买了什么」的来源。 */
    val description: String?,
    /** 平台侧状态，如「已全额退款」。再导入时靠它发现变化。 */
    val status: String?,
    /** 交易类型原文。用来判断是不是消费。 */
    val rawType: String?,
    /** 时间原文，仅用于排查。 */
    val rawTime: String?,
    val rowNumber: Int,
    /**
     * 这行说的是**一笔已经退回来的钱**，不是一笔新花出去的钱。
     *
     * 判据是配置里的 `refund_markers` 命中了**状态列或交易类型列**（微信实测的状态
     * 「已全额退款」「已退款¥6.60」、银行摘要里的「退款/退货」）。
     *
     * **金额列的负号不是判据**，这一点是刻意的：银行流水里普通的借方也常写成
     * `-38.50`，靠符号判会把正常支出认成退款，而那是静默少记一笔钱——
     * 比多记一笔更难发现。方向仍以收/支列（借贷标志）为准，符号照旧剥掉。
     */
    val isRefund: Boolean = false,
)

/** 一行为什么被排除。**排除必须给出理由**，否则用户无法判断是解析错了还是本就该跳过。 */
sealed interface RowVerdict {
    data class Consumption(val row: StatementRow) : RowVerdict

    /**
     * 这笔钱动了，但**不是一笔已发生的收支**：转账、红包、理财存取、信用卡还款
     * 这类资金转移，以及没成交（交易关闭）的那些。
     *
     * 两种都归这里而不是 [Unusable]，区别在于：这里说明「文件读懂了，是这行本来就不该入账」，
     * 而 [Unusable] 是「读不懂或缺字段，可能是我漏了什么」。用户对前者的正确反应是放心，
     * 对后者是来反馈。
     */
    data class NotConsumption(val row: StatementRow, val reason: String) : RowVerdict

    /** 缺必需字段（单号/金额/时间），无法入账。 */
    data class Unusable(val rowNumber: Int, val reason: String) : RowVerdict
}

/**
 * 一次解析的结果。
 *
 * [format] 为 null 表示**没认出来这是什么文件**——这时不要硬猜，
 * 让用户看到「认不出格式」比让他看到一堆错账好。
 */
data class ParseResult(
    val format: StatementFormat?,
    val verdicts: List<RowVerdict>,
    /** 解析过程中的告警，如「有 3 行金额为空，已跳过」。 */
    val warnings: List<String> = emptyList(),
) {
    val consumptionRows: List<StatementRow>
        get() = verdicts.filterIsInstance<RowVerdict.Consumption>().map { it.row }

    val usableRowCount: Int get() = verdicts.count { it !is RowVerdict.Unusable }
}

// ---------------------------------------------------------------- 格式定义

/**
 * 一种流水文件的格式。**这是配置，不是代码。**
 *
 * 加一家银行 = 加一条 JSON，不改代码。列名各家不同、还可能随版本变，
 * 写成 `if (header == "交易单号")` 就得每次都改代码。
 */
@Serializable
data class StatementFormat(
    val id: String,
    @SerialName("display_name") val displayName: String,
    /** 用于判断「这份文件是哪种格式」：表头里出现其中任意一个就归为这种。 */
    @SerialName("header_markers") val headerMarkers: List<String>,
    /** 候选编码，按顺序试。如 GBK 与 UTF-8。 */
    val encodings: List<String> = listOf("UTF-8", "GBK"),
    /** 逻辑字段 → 可能的列名（别名）。匹配时做归一化（去空格、全半角）。 */
    val columns: Map<String, List<String>>,
    /**
     * 少一个就入不了账的列。
     *
     * 缺列时应当**报出缺了哪一列**，而不是笼统地说"认不出格式"——
     * 前者用户能反馈给我们去加别名，后者只能得到一句没用的抱怨。
     */
    @SerialName("required_columns")
    val requiredColumns: List<String> = listOf(
        StatementColumns.REF,
        StatementColumns.TIME,
        StatementColumns.AMOUNT,
        StatementColumns.DIRECTION,
    ),
    /**
     * 「收/支」列里哪些值算支出、哪些算收入、哪些**既不是收入也不是支出**。
     *
     * 三个键：`expense` / `income` / `neutral`。第三档不是可选项——
     * 支付宝写「不计收支」、微信写 `/`，落到 `neutral` 才不会被当成"方向无法识别"而报错。
     */
    @SerialName("direction_values") val directionValues: Map<String, List<String>> = emptyMap(),
    /** 「交易类型」里这些取值属于**资金转移不是消费**，导入时要排除。 */
    @SerialName("non_consumption_types") val nonConsumptionTypes: List<String> = emptyList(),
    /**
     * 「交易状态」里这些取值说明**这笔没有成交**，导入时要排除。
     *
     * 只列点名的那些，**不要**写成"非成功即排除"：微信的「已全额退款」是成交后退的，
     * 钱确实动过，将来要靠它发现状态变化并冲减。
     */
    @SerialName("skip_statuses") val skipStatuses: List<String> = emptyList(),
    /**
     * 「状态」或「交易类型」列里出现其中任意一个，这行就是一笔**退款**。
     *
     * 微信实测的状态取值：`已全额退款`、`已退款¥6.60`，都被 `退款` 命中。
     * **不拿「商品说明」去对**——那列是自由文本，商户名或商品名里带「退款」二字的
     * 正常消费会中招，而误判的后果是静默少记一笔支出。
     */
    @SerialName("refund_markers") val refundMarkers: List<String> = emptyList(),
    /**
     * 状态里出现其中任意一个，说明是**全额**退款——冲减金额等于原消费金额。
     *
     * 与 [refundMarkers] 分开是因为两者回答的是不同问题：前者「这是不是退款」，
     * 这里「退了多少」。都不命中时退多少只能从状态文本里的金额去认（见 `RefundAmount`），
     * 认不出来就**不猜**，那行只展示不入库。
     */
    @SerialName("full_refund_markers") val fullRefundMarkers: List<String> = emptyList(),
    /** 金额列可能带这些前缀/修饰，解析前要剥掉。 */
    @SerialName("amount_strip") val amountStrip: List<String> = listOf("¥", "￥", ",", "，"),
    /** 时间列的格式，按顺序试。 */
    @SerialName("time_patterns") val timePatterns: List<String> = emptyList(),
)

/** 逻辑字段名。用常量而不是散落的字符串字面量。 */
object StatementColumns {
    const val TIME = "time"
    const val TYPE = "type"
    const val COUNTERPARTY = "counterparty"
    const val DESCRIPTION = "description"
    const val DIRECTION = "direction"
    const val AMOUNT = "amount"
    const val STATUS = "status"
    const val REF = "ref"
    const val MERCHANT_REF = "merchant_ref"
}

@Serializable
internal data class StatementFormatFile(
    val version: Int,
    val formats: List<StatementFormat>,
)
