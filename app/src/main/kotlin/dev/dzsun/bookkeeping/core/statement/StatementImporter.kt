package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.DuplicateCandidate
import dev.dzsun.bookkeeping.core.database.JournalEntity
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.ledger.ItemDraft
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.ledger.LedgerRepository
import dev.dzsun.bookkeeping.core.money.Money
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
 * 一笔**可以落库**的冲减。
 *
 * 这是 P1-d 补的形状：此前 `statusChanged` 里的退款行**只能看不能办**，
 * 用户没有任何办法在导入流程里处理那笔冲减，只能自己去账本里手记一笔——
 * 那正是导入想省掉的事。
 *
 * [draft] 是已经构造好的反向凭证（[JournalDraft.reversalOf] 的产物），
 * 界面把它连同勾选状态一起传回 `commit` 即可，不必自己拼分录。
 */
data class PlannedReversal(
    /** 触发它的那一行流水。 */
    val row: StatementRow,
    /**
     * 冲的是账本里**已有的**哪一笔。
     *
     * null 表示原消费是**同一批一起导入**的（首次导入就带着退款状态），
     * 它的凭证 id 要到落库时才产生，所以由 `commit` 在写完原消费后补上。
     */
    val existingJournalId: String?,
    /** 反向凭证。部分退款时冲的是退回来的那部分，不是全额。 */
    val draft: JournalDraft,
    /** 退回来多少钱。**从流水里认出来的**，认不出来时不会有这条 [PlannedReversal]。 */
    val refundAmount: Money,
    /** 原消费的分类名，给预览显示用。 */
    val categoryName: String,
) {
    /** 部分退款（平台状态里带了金额，比原消费少）。 */
    val isPartial: Boolean get() = refundAmount < row.amount
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
    /**
     * 可以落库的退款冲减。**按行与上面几个列表重叠，不是新增的行数**——
     * 原消费已在账本里的，那行同时也在 [statusChanged] 里；
     * 首次导入就带退款状态的，那行同时也在 [entries] 里。
     *
     * 所以 [totalRows] **不**把它再数一遍。之所以让它重叠而不把它挪走，
     * 是因为 `statusChanged` 若不再完整，现有的预览会**静默少显示几行**——
     * 用户看不到的那一行，比看到一行还不能办更糟。
     * 界面做勾选入口时按 `row.rowNumber` 与上面两个列表对上即可。
     */
    val reversals: List<PlannedReversal> = emptyList(),
    val excluded: List<RowVerdict.NotConsumption>,
    val unusable: List<RowVerdict.Unusable>,
) {
    val totalRows: Int
        get() = entries.size + duplicates.size + alreadyImportedCount +
            statusChanged.size + excluded.size + unusable.size
}

/** 落库成功的一行：把「刚导入的这笔」与「它的商户」对上。 */
data class PostedEntry(val entry: PlannedEntry, val journalId: String)

/**
 * 落库结果。[failures] 只记「写不进去」的，不含用户主动跳过的。
 *
 * [posted] 是**落库成功那些行的明细**，不是计数。批量归类要在导入**之后**改分类，
 * 那时手里只剩商户名；没有这层对应关系，就只能拿商户名回全表模糊搜——那是另一种猜，
 * 而且会把用户以前手记的同名商户一起改掉。
 *
 * 冲减（[PlannedReversal]）落库的不在这里：它没有商户语义，改分类无从谈起。
 */
data class ImportOutcome(
    val imported: Int,
    val failures: List<String>,
    val posted: List<PostedEntry> = emptyList(),
)

/** 「把文件变成解析结果」这一步的三种结局。 */
private sealed interface ParseAttempt {
    data class Parsed(val result: ParseResult) : ParseAttempt

    /** 文件读不了或密码不对——已经带上给用户看的说法。 */
    data class Refused(val preparation: ImportPreparation) : ParseAttempt

    /** 是加密账单但还没给密码。这不是错误，是流程里正常的一步。 */
    data object NeedsPassword : ParseAttempt
}

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
        when (val attempt = parseAny(request)) {
            ParseAttempt.NeedsPassword -> ImportPreparation.NeedsPassword
            is ParseAttempt.Refused -> attempt.preparation
            is ParseAttempt.Parsed -> analyze(attempt.result, request)
        }
    }

    /**
     * 先把文件变成 [ParseResult]，认不出格式时如实说。
     *
     * **顺序有讲究**：xlsx 也是 zip，而且里面没有 csv 条目——若先走
     * [StatementArchive]，它只会报「压缩包里没有找到 CSV 条目」，用户看到一句
     * 与他手上的文件对不上的话。所以凡是有 zip 特征的文件都先按 xlsx 试一次，
     * 试不出来（加密 zip、普通 zip+csv）再走原来的路。
     */
    private fun parseAny(request: ImportRequest): ParseAttempt {
        val formats = catalog.formats()

        if (!StatementArchive.isZip(request.bytes)) {
            return ParseAttempt.Parsed(CsvStatementParser(formats).parse(request.bytes))
        }

        XlsxStatementParser(formats).parse(request.bytes)
            .takeIf { it.format != null }
            ?.let { return ParseAttempt.Parsed(it) }

        return when (val extracted = StatementArchive.open(request.bytes, request.password)) {
            is ArchiveExtraction.Extracted ->
                ParseAttempt.Parsed(CsvStatementParser(formats).parse(extracted.bytes))
            ArchiveExtraction.NotZip ->
                ParseAttempt.Parsed(CsvStatementParser(formats).parse(request.bytes))
            is ArchiveExtraction.NeedsPassword -> ParseAttempt.NeedsPassword
            is ArchiveExtraction.WrongPassword ->
                ParseAttempt.Refused(ImportPreparation.WrongPassword(extracted.fileName))
            is ArchiveExtraction.Failed ->
                ParseAttempt.Refused(ImportPreparation.Unreadable(extracted.reason))
        }
    }

    private suspend fun analyze(parsed: ParseResult, request: ImportRequest): ImportPreparation {
        val format = parsed.format
            ?: return ImportPreparation.Unreadable(
                reason = "认不出这份账单的格式",
                warnings = parsed.warnings,
            )

        val entries = mutableListOf<PlannedEntry>()
        val duplicates = mutableListOf<PlannedEntry>()
        val statusChanged = mutableListOf<StatementRow>()
        val reversals = mutableListOf<PlannedReversal>()
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
                // 状态变了得**能办**，不只是看着。原消费就在账本里，直接照着它反向记一笔。
                planReversalOf(row, existing, format)?.let { reversals += it }
                continue
            }

            val planned = planEntry(row, request)
            if (planned.duplicateCandidates.isEmpty()) {
                entries += planned
                // 首次导入就带着退款状态：原消费也在这一批里，冲减到落库时才连得上它。
                // 只在原消费**确实会入库**时才提冲减——它要是疑似重复、默认不导入，
                // 冲减就成了一笔无主的负向凭证
                planReversalOfNewCharge(row, planned, format)?.let { reversals += it }
            } else {
                duplicates += planned
            }
        }

        return ImportPreparation.Ready(
            ImportPlan(
                formatId = format.id,
                formatName = format.displayName,
                warnings = parsed.warnings,
                entries = entries,
                duplicates = duplicates,
                alreadyImportedCount = alreadyImported,
                statusChanged = statusChanged,
                reversals = reversals,
                excluded = parsed.verdicts.filterIsInstance<RowVerdict.NotConsumption>(),
                unusable = parsed.verdicts.filterIsInstance<RowVerdict.Unusable>(),
            ),
        )
    }

    /**
     * 原消费**已经在账本里**，这行说它被退回来了。
     *
     * 照着原凭证的分录反向记一笔——这是唯一能保证「退款把原消费恰好抵消」的做法：
     * 账户与分类都取自原凭证，不重新猜一遍。若拿商户名重新归类，退款很可能落到
     * 另一个分类上，于是那个分类的支出虚高、原分类虚低，两边都错。
     *
     * 三种情况不产出冲减，**都不猜**：原凭证本身已是一笔冲减（不套娃）、
     * 这笔已经冲过了（不重复冲）、退款金额从流水里认不出来。
     */
    private suspend fun planReversalOf(
        row: StatementRow,
        existing: JournalEntity,
        format: StatementFormat,
    ): PlannedReversal? {
        if (existing.reversesJournalId != null) return null
        if (repository.isReversed(existing.id)) return null

        // 读回原凭证。读不出来（库里的账坏了）就不冲——把坏账放大一倍更糟
        val original = repository.draftOf(existing.id) ?: return null
        val chargeAmount = original.postings.firstOrNull()?.amount?.abs() ?: return null

        val refunded = RefundAmount.resolve(row.status, format, chargeAmount) ?: return null
        // 部分退款只支持一收一支的凭证；拆分凭证要按比例摊分，规则未定
        if (refunded < chargeAmount && original.postings.size != 2) return null

        return PlannedReversal(
            row = row,
            existingJournalId = existing.id,
            draft = JournalDraft.reversalOf(
                original = original,
                amount = refunded.takeIf { it < chargeAmount },
                source = JournalSource.STATEMENT,
                reversesJournalId = existing.id,
            ),
            refundAmount = refunded,
            categoryName = categoryNameOf(original),
        )
    }

    /**
     * 这行是退款，但账本里还没有对应的原消费——**首次导入就带着退款状态**。
     *
     * 那笔原消费也在这一批里（同一行既说明花了多少、又说明退回来了），
     * 所以冲减照它反向，落库时由 `commit` 把两者的 id 连起来。
     *
     * 只在**支出**方向做：方向写着收入的行，退款到底冲的是哪一笔账、
     * 该落到哪个分类都无从判断，宁可不动它（照旧当一笔收入导入）。
     */
    private suspend fun planReversalOfNewCharge(
        row: StatementRow,
        charge: PlannedEntry,
        format: StatementFormat,
    ): PlannedReversal? {
        if (row.direction != StatementDirection.EXPENSE) return null

        val chargeDraft = toJournalDraft(charge)
        val chargeAmount = row.amount
        val refunded = RefundAmount.resolve(row.status, format, chargeAmount) ?: return null

        return PlannedReversal(
            row = row,
            existingJournalId = null,
            draft = JournalDraft.reversalOf(
                original = chargeDraft,
                amount = refunded.takeIf { it < chargeAmount },
                source = JournalSource.STATEMENT,
            ),
            refundAmount = refunded,
            categoryName = charge.categoryName,
        )
    }

    /** 原凭证里那个收支分类账户的名字。冲减的分类就是它，不重新猜。 */
    private suspend fun categoryNameOf(original: JournalDraft): String {
        for (posting in original.postings) {
            val account = repository.accountOf(posting.accountId) ?: continue
            if (account.type == AccountType.EXPENSE || account.type == AccountType.INCOME) {
                return account.name
            }
        }
        return ""
    }

    private suspend fun planEntry(row: StatementRow, request: ImportRequest): PlannedEntry {
        val remembered = row.merchant?.let { repository.categoryForMerchant(it) }
        val fallback = when (row.direction) {
            StatementDirection.EXPENSE -> request.fallbackExpenseCategoryId
            StatementDirection.INCOME -> request.fallbackIncomeCategoryId
            // 不计收支的行在解析阶段就被判成 NotConsumption，走不到这里
            StatementDirection.NEUTRAL -> error("不计收支的行不该进入入账计划（第 ${row.rowNumber} 行）")
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

    /**
     * 把用户确认过的行落库。传进来的才写，其余一概不动。
     *
     * [reversals] 是用户勾选了的退款冲减。顺序不能颠倒：**先写原消费，再写冲减**，
     * 因为「首次导入就带着退款状态」那些行的冲减要拿原消费的凭证 id 才连得上。
     * 冲减在原消费之前写的话，`reversesJournalId` 就是空的——一笔无主的负向凭证，
     * 既说不清冲的是谁，也没法保证不重复冲。
     */
    suspend fun commit(
        entries: List<PlannedEntry>,
        reversals: List<PlannedReversal> = emptyList(),
    ): ImportOutcome = withContext(Dispatchers.IO) {
        var imported = 0
        val failures = mutableListOf<String>()
        val posted = mutableListOf<PostedEntry>()
        val postedByKey = mutableMapOf<Pair<String, String>, String>()

        for (entry in entries) {
            runCatching { repository.post(toJournalDraft(entry)) }
                .onSuccess { id ->
                    imported++
                    posted += PostedEntry(entry, id)
                    postedByKey[entry.row.externalSource to entry.row.externalRef] = id
                }
                .onFailure { failures += "第 ${entry.row.rowNumber} 行入账失败：${it.message}" }
        }

        for (reversal in reversals) {
            val originalId = reversal.existingJournalId
                ?: postedByKey[reversal.row.externalSource to reversal.row.externalRef]
            if (originalId == null) {
                failures += "第 ${reversal.row.rowNumber} 行的退款冲减失败：本次没有导入它所冲的那笔原消费"
                continue
            }
            runCatching {
                repository.postReversal(
                    draft = reversal.draft.copy(reversesJournalId = originalId),
                    // 冲减之后要把原凭证的平台状态推到最新，否则下次导入比对状态
                    // 又会认为「变了」，于是再冲一次
                    originalExternalStatus = reversal.row.status,
                )
            }
                .onSuccess { imported++ }
                .onFailure { failures += "第 ${reversal.row.rowNumber} 行的退款冲减失败：${it.message}" }
        }

        ImportOutcome(imported, failures, posted)
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

        // 复式记账的两个方向之外没有第三种可能。解析层已把「不计收支」挡在
        // NotConsumption 里，所以这里是不该发生的——让它响，别让它默默记成一笔支出。
        StatementDirection.NEUTRAL -> error("不计收支的行不该入账（第 ${row.rowNumber} 行）")
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
