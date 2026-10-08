package dev.dzsun.bookkeeping.feature.importer

import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.statement.ImportPlan
import dev.dzsun.bookkeeping.core.statement.PlannedEntry
import dev.dzsun.bookkeeping.core.statement.RowVerdict
import dev.dzsun.bookkeeping.core.statement.StatementDirection
import dev.dzsun.bookkeeping.core.statement.StatementLabeling
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 预览页顶部那几个数字。
 *
 * 从 [ImportPlan] 折叠出来，**单独一层是为了能纯 JVM 测**——
 * 「将跳过多少笔、合计多少钱」算错的话，用户会在确认前做出错误决定，
 * 而那正是整个「先预览再落库」设计要防的事。
 */
data class ImportSummary(
    val totalRows: Int,
    val importCount: Int,
    val importAmountMinor: Long,
    val importExpenseCount: Int,
    val importExpenseMinor: Long,
    val importIncomeCount: Int,
    val importIncomeMinor: Long,
    val skipDuplicateCount: Int,
    val skipDuplicateAmountMinor: Long,
    val alreadyImportedCount: Int,
    val statusChangedCount: Int,
    val excludedCount: Int,
    val unusableCount: Int,
    val currency: String,
) {
    /** 确认按钮上的那句：没有可导入的就别让人点。 */
    val importable: Boolean get() = importCount > 0
}

/**
 * 把解析计划折叠成用户一眼能看懂的汇总。
 *
 * [currency] 取本位币——流水金额本身带币种，但一份账单只有一种货币，
 * 汇总里没必要按币种分组。
 */
fun ImportPlan.toSummary(currency: String): ImportSummary {
    var expenseCount = 0
    var expenseMinor = 0L
    var incomeCount = 0
    var incomeMinor = 0L
    for (e in entries) {
        when (e.row.direction) {
            StatementDirection.EXPENSE -> {
                expenseCount++
                expenseMinor += e.row.amount.amountMinor
            }
            StatementDirection.INCOME -> {
                incomeCount++
                incomeMinor += e.row.amount.amountMinor
            }
            // 不计收支的行（充值/提现/还款）在解析阶段就判成 NotConsumption，
            // 进不了 entries，所以这里到不了。见 core/statement/StatementRowParser.kt
            StatementDirection.NEUTRAL -> Unit
        }
    }
    return ImportSummary(
        totalRows = totalRows,
        importCount = entries.size,
        importAmountMinor = expenseMinor + incomeMinor,
        importExpenseCount = expenseCount,
        importExpenseMinor = expenseMinor,
        importIncomeCount = incomeCount,
        importIncomeMinor = incomeMinor,
        skipDuplicateCount = duplicates.size,
        skipDuplicateAmountMinor = duplicates.sumOf { it.row.amount.amountMinor },
        alreadyImportedCount = alreadyImportedCount,
        statusChangedCount = statusChanged.size,
        excludedCount = excluded.size,
        unusableCount = unusable.size,
        currency = currency,
    )
}

/**
 * 预览列表里的一行。区分 [kind] 是为了让重复项能显式标上「将跳过」——
 * 用户不该在确认前以为全部入库。
 */
data class PreviewRow(
    val kind: Kind,
    val rowNumber: Int,
    val dateEpochDay: Long,
    val direction: StatementDirection,
    val amountMinor: Long,
    val merchant: String?,
    val description: String?,
    /** 账单自带的「交易分类」列（如「餐饮美食」）。两列都没信息时用它当标题。 */
    val rawType: String?,
    val categoryName: String?,
    val categoryFromHistory: Boolean,
    /** 为什么被跳过/排除；待导入的行是 null。 */
    val skipReason: String?,
    val duplicateCount: Int = 0,
) {
    enum class Kind {
        /** 会入库。 */
        IMPORT,

        /** 有重复候选，**默认不导入**。 */
        DUPLICATE,

        /** 之前导入过、且状态变了（多半是退款），只展示不入库。 */
        STATUS_CHANGED,

        /** 转账/红包这类资金转移。 */
        EXCLUDED,

        /** 缺字段，解析不了。 */
        UNUSABLE,
    }
}

/** 「将跳过」的统一文案——预览里出现的所有跳过标签都从这走，免得各写各的。 */
const val LABEL_WILL_SKIP = "将跳过"

/** 预览行标题：[primary] 是主标题，[secondary] 是补充信息（没有则为 null）。 */
data class PreviewTitle(val primary: String, val secondary: String?)

/**
 * 预览行的标题怎么取。
 *
 * 三条规则，按「这行还剩多少信息」依次降级：
 * - 商户有信息量：商户做标题，**商品说明降为副标题**——它正是「买了什么」的来源，不能丢；
 * - 商户没有信息量（被截断成 1–2 字等）：商品说明做标题，截断名降为副标题；
 * - **两列都没有信息量**（如「飞」+「收钱码收款」）：用账单自带的「交易分类」
 *   （「餐饮美食」）做标题，见 [StatementLabeling.statementTypeName]。
 *   这两列都说了等于没说，摆出来只会占地方。
 *
 * 抽成纯函数是为了能测——取错了不会报错，只会让用户对着一个孤零零的「飞」
 * 或者一句「收钱码收款」不知道这是哪笔钱。
 */
fun previewTitle(row: PreviewRow): PreviewTitle {
    val merchant = row.merchant?.trim().orEmpty()
    val description = row.description?.trim().orEmpty()

    StatementLabeling.statementTypeName(merchant, description, row.rawType)
        ?.let { return PreviewTitle(it, null) }

    val merchantInformative = !StatementLabeling.isUninformativeMerchant(merchant)
    return when {
        merchantInformative ->
            PreviewTitle(merchant, description.takeIf { it.isNotEmpty() && it != merchant })

        description.isNotEmpty() ->
            PreviewTitle(description, merchant.takeIf { it.isNotEmpty() && it != description })

        merchant.isNotEmpty() -> PreviewTitle(merchant, null)

        else -> PreviewTitle("第 ${row.rowNumber} 行", null)
    }
}

/** 把计划摊平成预览列表：先待导入，再疑似重复，最后是不入库的其他行。 */
fun ImportPlan.toPreviewRows(): List<PreviewRow> = buildList {
    for (e in entries) {
        add(e.toPreviewRow(PreviewRow.Kind.IMPORT, skipReason = null))
    }
    for (e in duplicates) {
        add(
            e.toPreviewRow(
                PreviewRow.Kind.DUPLICATE,
                skipReason = LABEL_WILL_SKIP,
                duplicateCount = e.duplicateCandidates.size,
            ),
        )
    }
    for (row in statusChanged) {
        add(
            PreviewRow(
                kind = PreviewRow.Kind.STATUS_CHANGED,
                rowNumber = row.rowNumber,
                dateEpochDay = row.dateEpochDay,
                direction = row.direction,
                amountMinor = row.amount.amountMinor,
                merchant = row.merchant,
                description = row.description,
                rawType = row.rawType,
                categoryName = null,
                categoryFromHistory = false,
                skipReason = "状态变化 · 需单独处理",
            ),
        )
    }
    for (v in excluded) {
        val row = v.row
        add(
            PreviewRow(
                kind = PreviewRow.Kind.EXCLUDED,
                rowNumber = row.rowNumber,
                dateEpochDay = row.dateEpochDay,
                direction = row.direction,
                amountMinor = row.amount.amountMinor,
                merchant = row.merchant,
                description = row.description,
                rawType = row.rawType,
                categoryName = null,
                categoryFromHistory = false,
                skipReason = v.reason,
            ),
        )
    }
    for (v in unusable) {
        add(
            PreviewRow(
                kind = PreviewRow.Kind.UNUSABLE,
                rowNumber = v.rowNumber,
                dateEpochDay = 0L,
                direction = StatementDirection.EXPENSE,
                amountMinor = 0L,
                merchant = null,
                description = null,
                rawType = null,
                categoryName = null,
                categoryFromHistory = false,
                skipReason = v.reason,
            ),
        )
    }
}

private fun PlannedEntry.toPreviewRow(
    kind: PreviewRow.Kind,
    skipReason: String?,
    duplicateCount: Int = 0,
): PreviewRow = PreviewRow(
    kind = kind,
    rowNumber = row.rowNumber,
    dateEpochDay = row.dateEpochDay,
    direction = row.direction,
    amountMinor = row.amount.amountMinor,
    merchant = row.merchant,
    description = row.description,
    rawType = row.rawType,
    categoryName = categoryName,
    categoryFromHistory = categoryFromHistory,
    skipReason = skipReason,
    duplicateCount = duplicateCount,
)

// ---------------------------------------------------------------- 文案

private val previewDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

fun formatPreviewDate(epochDay: Long): String =
    if (epochDay <= 0L) "—" else LocalDate.ofEpochDay(epochDay).format(previewDateFormatter)

/** 带方向的金额文本：支出 `-¥38.50`、收入 `+¥8,000.00`。 */
fun formatSignedAmount(minor: Long, direction: StatementDirection, currency: String): String {
    val abs = Money.of(if (minor < 0) -minor else minor, currency).format()
    val sign = if (direction == StatementDirection.INCOME) "+" else "-"
    return sign + abs
}

/** 跳过/排除原因的统一样式。没有原因就返回 null，界面不显示标签。 */
fun skipLabel(row: PreviewRow): String? = when (row.kind) {
    PreviewRow.Kind.IMPORT -> null
    PreviewRow.Kind.DUPLICATE -> buildString {
        append(LABEL_WILL_SKIP)
        if (row.duplicateCount > 0) append(" · 疑似重复 ${row.duplicateCount} 笔")
    }
    PreviewRow.Kind.STATUS_CHANGED -> "状态变化"
    PreviewRow.Kind.EXCLUDED -> "不入库"
    PreviewRow.Kind.UNUSABLE -> "无法解析"
}

/**
 * 认不出商户时的兜底分类：优先找名字对得上的「其他支出」/「其他收入」，
 * 找不到再退到该类型的第一个。**分类是数据不是代码**，所以不写死 id。
 */
fun pickFallbackCategoryId(categories: List<AccountEntity>, preferredName: String): String? {
    if (categories.isEmpty()) return null
    return categories.firstOrNull { it.name == preferredName }?.id
        ?: categories.firstOrNull()?.id
}

fun List<AccountEntity>.fallbackExpenseId(): String? =
    pickFallbackCategoryId(this, "其他支出")

fun List<AccountEntity>.fallbackIncomeId(): String? =
    pickFallbackCategoryId(this, "其他收入")

/** 账单对应的账户：资产与负债都算——信用卡账单就该落到「信用卡」上。 */
val IMPORT_ACCOUNT_TYPES = listOf(AccountType.ASSET, AccountType.LIABILITY)

/**
 * 密码错误时的提示。**要能对用户说清楚**，而不是笼统的"失败"——
 * 银行流水 zip 的密码是用户自己设的，说清楚他才知道去哪找。
 */
const val WRONG_PASSWORD_MESSAGE = "密码不对，请重试"

const val UNREADABLE_PREFIX = "读不了这份文件："
