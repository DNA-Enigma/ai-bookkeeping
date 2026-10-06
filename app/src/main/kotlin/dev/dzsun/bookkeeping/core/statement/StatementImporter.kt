package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.DuplicateCandidate
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.ItemDraft
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导入一份流水需要的输入。 */
data class ImportRequest(
    val bytes: ByteArray,
    /** 加密账单的密码。null 表示还没问过用户。 */
    val password: String?,
    /** 这份流水对应的账户，如导入微信账单就选「微信钱包」。流水里没有这个信息。 */
    val accountId: String,
    /** 认不出商户时落在哪个分类。 */
    val fallbackExpenseCategoryId: String,
    val fallbackIncomeCategoryId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ImportRequest && other.accountId == accountId &&
            other.password == password && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + accountId.hashCode()
}

/** 准备阶段的结果。每一种失败都要能对用户说清楚。 */
sealed interface ImportPreparation {
    data class Ready(val plan: ImportPlan) : ImportPreparation

    /** 是加密账单但还没给密码——界面据此弹密码框，这不是错误。 */
    data object NeedsPassword : ImportPreparation

    data class WrongPassword(val fileName: String) : ImportPreparation

    /** 文件读不了、格式认不出。 */
    data class Unreadable(val reason: String, val warnings: List<String> = emptyList()) : ImportPreparation
}

/**
 * 待导入的一行，**已解析出分类**。
 *
 * [categoryFromHistory] 为 true 表示分类来自用户自己以前的归类，不是猜的。
 */
data class PlannedEntry(
    val row: StatementRow,
    /** 这份流水对应的账户。流水里没有这个信息，是导入时由用户选的。 */
    val accountId: String,
    val categoryId: String,
    val categoryName: String,
    val categoryFromHistory: Boolean,
    /** 可能与它重复的既有账目。非空时**默认不导入**，交用户决定。 */
    val duplicateCandidates: List<DuplicateCandidate>,
) {
    /** 流水里的「商品」当作明细——它正是「几周后想起买了什么」要用的那行字。 */
    val items: List<ItemDraft>
        get() = row.description?.takeIf { it.isNotBlank() }?.let { listOf(ItemDraft(it)) } ?: emptyList()
}

/**
 * 一次导入的计划。**先给用户看，确认后才落库。**
 *
 * 导入动辄几百行，直接写进去出了错很难收拾；而预览里能顺手回答
 * 「哪些是重复的」「哪些被排除了」，比事后去账本里找便宜得多。
 */
data class ImportPlan(
    val formatId: String?,
    val formatName: String?,
    val warnings: List<String>,
    /** 可以直接导入的。 */
    val entries: List<PlannedEntry>,
    /** 有重复候选的，默认不导入——**只给候选，不替用户决定**。 */
    val duplicates: List<PlannedEntry>,
    /** 之前导入过、且状态没变的，跳过。 */
    val alreadyImportedCount: Int,
    /** 之前导入过、但**状态变了**（多半是退款）的，得让用户看到。 */
    val statusChanged: List<StatementRow>,
    val excluded: List<RowVerdict.NotConsumption>,
    val unusable: List<RowVerdict.Unusable>,
) {
    val totalRows: Int
        get() = entries.size + duplicates.size + alreadyImportedCount +
            statusChanged.size + excluded.size + unusable.size
}

/** 落库结果。[failures] 只记「写不进去」的，不含用户主动跳过的。 */
data class ImportOutcome(
    val imported: Int,
    val failures: List<String>,
)

/**
 * 把流水文件变成账目。
 *
 * 分两步是有意的：`prepare` 只读不写，把所有需要用户决定的事摆出来；
 * `commit` 才落库，且只落用户点了头的那些。
 */
@Singleton
class StatementImporter @Inject constructor(
    private val repository: LedgerRepository,
    private val catalog: StatementFormatCatalog,
) {

    /** 解析并分析，**不落库**。 */
    suspend fun prepare(request: ImportRequest): ImportPreparation = withContext(Dispatchers.IO) {
        val csvBytes = when (val extracted = StatementArchive.open(request.bytes, request.password)) {
            is ArchiveExtraction.Extracted -> extracted.bytes
            ArchiveExtraction.NotZip -> request.bytes
            is ArchiveExtraction.NeedsPassword -> return@withContext ImportPreparation.NeedsPassword
            is ArchiveExtraction.WrongPassword -> return@withContext ImportPreparation.WrongPassword(extracted.fileName)
            is ArchiveExtraction.Failed -> return@withContext ImportPreparation.Unreadable(extracted.reason)
        }

        val parsed = CsvStatementParser(catalog.formats()).parse(csvBytes)
        if (parsed.format == null) {
            return@withContext ImportPreparation.Unreadable(
                reason = "认不出这份账单的格式",
                warnings = parsed.warnings,
            )
        }

        val entries = mutableListOf<PlannedEntry>()
        val duplicates = mutableListOf<PlannedEntry>()
        val statusChanged = mutableListOf<StatementRow>()
        var alreadyImported = 0

        for (row in parsed.consumptionRows) {
            val existing = repository.findImported(row.externalSource, row.externalRef)
            if (existing != null) {
                // ⚠️ 已导入**不等于**跳过：同一笔在两次导出里内容会变，
                // 退款后原行状态会变成「已全额退款」。跳过就会漏掉那笔冲减。
                if (existing.externalStatus != row.status) {
                    statusChanged += row
                } else {
                    alreadyImported++
                }
                continue
            }

            val planned = planEntry(row, request)
            if (planned.duplicateCandidates.isEmpty()) entries += planned else duplicates += planned
        }

        ImportPreparation.Ready(
            ImportPlan(
                formatId = parsed.format.id,
                formatName = parsed.format.displayName,
                warnings = parsed.warnings,
                entries = entries,
                duplicates = duplicates,
                alreadyImportedCount = alreadyImported,
                statusChanged = statusChanged,
                excluded = parsed.verdicts.filterIsInstance<RowVerdict.NotConsumption>(),
                unusable = parsed.verdicts.filterIsInstance<RowVerdict.Unusable>(),
            ),
        )
    }

    private suspend fun planEntry(row: StatementRow, request: ImportRequest): PlannedEntry {
        val remembered = row.merchant?.let { repository.categoryForMerchant(it) }
        val fallback = when (row.direction) {
            StatementDirection.EXPENSE -> request.fallbackExpenseCategoryId
            StatementDirection.INCOME -> request.fallbackIncomeCategoryId
        }
        val categoryId = remembered ?: fallback
        val name = repository.accountName(categoryId).orEmpty()

        val candidates = repository.findDuplicateCandidates(
            categorySideAmount = row.amount,
            onDate = java.time.LocalDate.ofEpochDay(row.dateEpochDay),
        )

        return PlannedEntry(
            row = row,
            accountId = request.accountId,
            categoryId = categoryId,
            categoryName = name,
            categoryFromHistory = remembered != null,
            duplicateCandidates = candidates,
        )
    }

    /** 把用户确认过的行落库。传进来的才写，其余一概不动。 */
    suspend fun commit(entries: List<PlannedEntry>): ImportOutcome = withContext(Dispatchers.IO) {
        var imported = 0
        val failures = mutableListOf<String>()
        for (entry in entries) {
            runCatching { repository.post(toJournalDraft(entry)) }
                .onSuccess { imported++ }
                .onFailure { failures += "第 ${entry.row.rowNumber} 行入账失败：${it.message}" }
        }
        ImportOutcome(imported, failures)
    }

}

/**
 * 把一个待导入行映射成凭证。
 *
 * 抽成顶层函数是为了能在**纯 JVM 里直接测**——这段逻辑最容易写错：
 * 账户、分类、状态、外部字段、明细，哪一样错了都是静默的，只有对账时才发现。
 */
internal fun toJournalDraft(entry: PlannedEntry): JournalDraft {
    val row = entry.row
    val base = when (row.direction) {
        StatementDirection.EXPENSE -> JournalDraft.expense(
            dateEpochDay = row.dateEpochDay,
            amount = row.amount,
            fromAccountId = entry.accountId,
            categoryAccountId = entry.categoryId,
            payee = row.merchant,
            note = row.description,
            source = JournalSource.STATEMENT,
        )

        StatementDirection.INCOME -> JournalDraft.income(
            dateEpochDay = row.dateEpochDay,
            amount = row.amount,
            toAccountId = entry.accountId,
            categoryAccountId = entry.categoryId,
            payee = row.merchant,
            note = row.description,
            source = JournalSource.STATEMENT,
        )
    }
    return base.copy(
        // 分类来自用户以前的选择才敢置「已核对」；来自兜底的是猜的，留待确认。
        // 这条区分有实际意义：导入 200 行时，全是兜底分类的那些值得回头看一眼，
        // 而按历史归好类的没必要再看第二遍。
        status = if (entry.categoryFromHistory) JournalStatus.CLEARED else JournalStatus.PENDING,
        externalSource = row.externalSource,
        externalRef = row.externalRef,
        externalStatus = row.status,
        items = entry.items,
    )
}
