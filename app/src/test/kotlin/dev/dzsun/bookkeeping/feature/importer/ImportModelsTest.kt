package dev.dzsun.bookkeeping.feature.importer

import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.DuplicateCandidate
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.statement.ImportPlan
import dev.dzsun.bookkeeping.core.statement.PlannedEntry
import dev.dzsun.bookkeeping.core.statement.RowVerdict
import dev.dzsun.bookkeeping.core.statement.StatementDirection
import dev.dzsun.bookkeeping.core.statement.StatementRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览汇总与跳过标注。
 *
 * 「将跳过多少笔、合计多少钱」算错的话，用户会在确认前做出错误决定——
 * 而整个「先预览再落库」的设计就是为了防这个。所以这些数字必须有测试。
 */
class ImportModelsTest {

    private fun row(
        amount: String = "38.50",
        direction: StatementDirection = StatementDirection.EXPENSE,
        merchant: String? = "星巴克咖啡",
        description: String? = "拿铁 大杯",
        rowNumber: Int = 5,
        rawType: String? = "商户消费",
    ) = StatementRow(
        externalSource = "wechat",
        externalRef = "ref-$rowNumber",
        dateEpochDay = 20_000,
        amount = Money.parse(amount, "CNY"),
        direction = direction,
        merchant = merchant,
        description = description,
        status = "支付成功",
        rawType = rawType,
        rawTime = "2026-09-15 08:12:33",
        rowNumber = rowNumber,
    )

    private fun planned(
        row: StatementRow,
        duplicates: List<DuplicateCandidate> = emptyList(),
        categoryFromHistory: Boolean = false,
    ) = PlannedEntry(
        row = row,
        accountId = "asset.wechat",
        categoryId = "expense.other",
        categoryName = "其他支出",
        categoryFromHistory = categoryFromHistory,
        duplicateCandidates = duplicates,
    )

    private fun plan(
        entries: List<PlannedEntry> = emptyList(),
        duplicates: List<PlannedEntry> = emptyList(),
        alreadyImportedCount: Int = 0,
        statusChanged: List<StatementRow> = emptyList(),
        excluded: List<RowVerdict.NotConsumption> = emptyList(),
        unusable: List<RowVerdict.Unusable> = emptyList(),
    ) = ImportPlan(
        formatId = "wechat",
        formatName = "微信支付账单",
        warnings = emptyList(),
        entries = entries,
        duplicates = duplicates,
        alreadyImportedCount = alreadyImportedCount,
        statusChanged = statusChanged,
        excluded = excluded,
        unusable = unusable,
    )

    // —— 汇总数字 ——

    @Test
    fun `汇总把待导入与跳过分得清清楚楚`() {
        val summary = plan(
            entries = listOf(
                planned(row(amount = "38.50", rowNumber = 1)),
                planned(row(amount = "12.00", direction = StatementDirection.INCOME, rowNumber = 2)),
            ),
            duplicates = listOf(planned(row(amount = "50.00", rowNumber = 3))),
            alreadyImportedCount = 4,
            statusChanged = listOf(row(amount = "9.90", rowNumber = 4)),
            excluded = listOf(
                RowVerdict.NotConsumption(row(rowNumber = 5), reason = "转账"),
            ),
            unusable = listOf(RowVerdict.Unusable(rowNumber = 6, reason = "缺金额")),
        ).toSummary("CNY")

        assertEquals(2 + 1 + 4 + 1 + 1 + 1, summary.totalRows)
        assertEquals(2, summary.importCount)
        // 38.50 + 12.00 = 50.50（绝对值合计）
        assertEquals(5_050L, summary.importAmountMinor)
        assertEquals(3_850L, summary.importExpenseMinor)
        assertEquals(1_200L, summary.importIncomeMinor)
        assertEquals(1, summary.skipDuplicateCount)
        assertEquals(5_000L, summary.skipDuplicateAmountMinor)
        assertEquals(4, summary.alreadyImportedCount)
        assertEquals(1, summary.statusChangedCount)
        assertEquals(1, summary.excludedCount)
        assertEquals(1, summary.unusableCount)
        assertTrue(summary.importable)
    }

    @Test
    fun `没有可导入的行时确认按钮该禁用`() {
        val summary = plan(
            duplicates = listOf(planned(row())),
            alreadyImportedCount = 3,
        ).toSummary("CNY")
        assertEquals(0, summary.importCount)
        assertFalse(summary.importable)
    }

    @Test
    fun `空计划不会炸`() {
        val summary = plan().toSummary("CNY")
        assertEquals(0, summary.totalRows)
        assertEquals(0L, summary.importAmountMinor)
        assertFalse(summary.importable)
    }

    // —— 预览行与「将跳过」标注 ——

    @Test
    fun `重复项显式标注将跳过——确认前不该以为全部入库`() {
        val candidate = DuplicateCandidate(
            journalId = "j1",
            dateEpochDay = 20_000,
            payee = "星巴克",
            source = JournalSource.MANUAL,
            amountMinor = 3_850,
            currency = "CNY",
            categoryName = "餐饮",
            dayDistance = 0,
        )
        val rows = plan(
            entries = listOf(planned(row(rowNumber = 1))),
            duplicates = listOf(
                planned(row(amount = "50.00", rowNumber = 2), duplicates = listOf(candidate)),
            ),
        ).toPreviewRows()

        assertEquals(2, rows.size)
        assertEquals(PreviewRow.Kind.IMPORT, rows[0].kind)
        assertNull(skipLabel(rows[0]))

        assertEquals(PreviewRow.Kind.DUPLICATE, rows[1].kind)
        assertEquals(LABEL_WILL_SKIP, rows[1].skipReason)
        val label = skipLabel(rows[1])
        assertTrue(label != null && label.contains(LABEL_WILL_SKIP))
        assertTrue(label != null && label.contains("疑似重复 1 笔"))
    }

    @Test
    fun `不入库的行各带各的理由，不是笼统一句失败`() {
        val rows = plan(
            excluded = listOf(RowVerdict.NotConsumption(row(rowNumber = 1), reason = "转账")),
            unusable = listOf(RowVerdict.Unusable(rowNumber = 2, reason = "缺交易单号")),
            statusChanged = listOf(row(rowNumber = 3)),
        ).toPreviewRows()

        assertEquals(3, rows.size)
        // 顺序：状态变化 → 排除 → 无法解析
        assertEquals(PreviewRow.Kind.STATUS_CHANGED, rows[0].kind)
        assertTrue(rows[0].skipReason!!.contains("状态变化"))
        assertEquals(PreviewRow.Kind.EXCLUDED, rows[1].kind)
        assertEquals("转账", rows[1].skipReason)
        assertEquals(PreviewRow.Kind.UNUSABLE, rows[2].kind)
        assertEquals("缺交易单号", rows[2].skipReason)
    }

    @Test
    fun `列表顺序是待导入 → 重复 → 其他，确认时的主力数据在最前`() {
        val rows = plan(
            entries = listOf(planned(row(rowNumber = 1))),
            duplicates = listOf(planned(row(rowNumber = 2))),
            statusChanged = listOf(row(rowNumber = 3)),
            excluded = listOf(RowVerdict.NotConsumption(row(rowNumber = 4), "红包")),
        ).toPreviewRows()
        assertEquals(
            listOf(
                PreviewRow.Kind.IMPORT,
                PreviewRow.Kind.DUPLICATE,
                PreviewRow.Kind.STATUS_CHANGED,
                PreviewRow.Kind.EXCLUDED,
            ),
            rows.map { it.kind },
        )
    }

    // —— 标题：账单没给名字时用交易分类 ——

    @Test
    fun `计划摊平成预览行时交易分类要带过来`() {
        // 不带过来的话，标题规则里那个最关键的输入恒为 null，修复会静默失效
        val rows = plan(
            entries = listOf(
                planned(
                    row(merchant = "飞", description = "收钱码收款", rawType = "餐饮美食"),
                ),
            ),
        ).toPreviewRows()

        assertEquals("餐饮美食", rows[0].rawType)
        assertEquals("餐饮美食", previewTitle(rows[0]).primary)
    }

    @Test
    fun `被排除的行也带交易分类_只是它们不进账本`() {
        val rows = plan(
            excluded = listOf(
                RowVerdict.NotConsumption(
                    row(merchant = "/", description = "转账", rawType = "转账", rowNumber = 1),
                    reason = "资金转移",
                ),
            ),
        ).toPreviewRows()

        assertEquals("转账", rows[0].rawType)
    }

    @Test
    fun `用分类当标题的行不该被标成按历史`() {
        // 分类是账单给的，不是用户历史，也不是 AI 猜的——「（按历史）」那个后缀会撒谎
        val rows = plan(
            entries = listOf(
                planned(
                    row(merchant = "飞", description = "收钱码收款", rawType = "餐饮美食"),
                    categoryFromHistory = false,
                ),
            ),
        ).toPreviewRows()

        assertFalse(rows[0].categoryFromHistory)
        assertEquals("餐饮美食", previewTitle(rows[0]).primary)
    }

    // —— 文案 ——

    @Test
    fun `金额带方向符号，收入加号支出减号`() {
        assertEquals("-¥38.50", formatSignedAmount(3_850L, StatementDirection.EXPENSE, "CNY"))
        assertEquals("+¥8,000.00", formatSignedAmount(800_000L, StatementDirection.INCOME, "CNY"))
    }

    @Test
    fun `日期格式成几月几日，缺日期的行不硬凑`() {
        val text = formatPreviewDate(20_000)
        assertTrue(text.contains("月"))
        assertTrue(text.contains("日"))
        assertEquals("—", formatPreviewDate(0L))
    }

    // —— 兜底分类 ——

    @Test
    fun `兜底分类优先找其他支出与其他收入，而不是写死 id`() {
        val categories = listOf(
            account("expense.food", "餐饮"),
            account("expense.other", "其他支出"),
            account("expense.medical", "医疗"),
        )
        assertEquals("expense.other", categories.fallbackExpenseId())
    }

    @Test
    fun `没有其他支出时退到该类型第一个`() {
        val categories = listOf(
            account("expense.food", "餐饮"),
            account("expense.medical", "医疗"),
        )
        assertEquals("expense.food", categories.fallbackExpenseId())
    }

    @Test
    fun `收入兜底找其他收入`() {
        val categories = listOf(
            account("income.salary", "工资"),
            account("income.other", "其他收入"),
        )
        assertEquals("income.other", categories.fallbackIncomeId())
    }

    private fun account(id: String, name: String) = AccountEntity(
        id = id,
        name = name,
        type = if (id.startsWith("expense")) AccountType.EXPENSE else AccountType.INCOME,
        currency = "CNY",
    )
}
