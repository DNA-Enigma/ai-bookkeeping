package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「一堆已经切成单元格的行」→ 归一化的流水。
 *
 * CSV 与 xlsx 的区别只在前一步（怎么把字节切成单元格），**判定逻辑完全一样**：
 * 找表头、认格式、对列名、判收支、排除非消费。所以那部分放在这里共用，
 * 两种输入各写各的「切格子」。
 *
 * 两边共用同一份判定还有个实际好处：三家渠道的取值差异全部落在
 * `statement_formats.json` 里，加一家不用改这里一行。
 */
internal class StatementRowParser(private val formats: List<StatementFormat>) {

    /**
     * @param rows 每行的单元格。**下标必须与文件里的行号一一对应**——[StatementRow.rowNumber]
     *   与告警都靠它，缺行会让用户拿着错误的行号去文件里找。
     * @param fullText 全文，用于兜底认格式（有些账单的平台名只在说明行里出现）
     */
    fun parse(rows: List<List<String>>, fullText: String): ParseResult {
        val headerIndex = findHeaderRow(rows)
            ?: return ParseResult(
                format = null,
                verdicts = emptyList(),
                warnings = listOf("找不到表头行：这份文件可能不是账单，或格式尚未支持"),
            )

        val headerCells = rows[headerIndex].map { normalize(it) }
        val headerLine = headerCells.joinToString(",")

        // 先按表头行认格式，认不出再用全文的特征词兜底
        val format = matchFormat(headerLine, fullText)
            ?: return ParseResult(
                format = null,
                verdicts = emptyList(),
                warnings = listOf("认不出这是哪种账单格式（表头：${headerLine.take(80)}…）"),
            )

        val columnIndex = resolveColumns(format, headerCells)
        val missing = format.requiredColumns.filter { it !in columnIndex }
        if (missing.isNotEmpty()) {
            return ParseResult(
                format = format,
                verdicts = emptyList(),
                warnings = listOf("表头里缺少必需列：$missing（实际表头：${headerCells.take(12)}）"),
            )
        }

        val warnings = mutableListOf<String>()
        val verdicts = mutableListOf<RowVerdict>()
        var skippedNotes = 0

        for (index in headerIndex + 1 until rows.size) {
            val cells = rows[index]
            // 整行皆空的是空行，不算「说明/统计行」——把它计进去只会让告警数字虚高
            if (cells.all { it.isBlank() }) continue
            // 列数对不上的是说明行或统计行（账单末尾常见「共 N 笔」），静默跳过
            if (cells.size != headerCells.size) {
                skippedNotes++
                continue
            }
            verdicts += judge(format, columnIndex, cells, index + 1)
        }

        if (skippedNotes > 0) {
            warnings += "跳过了 $skippedNotes 行说明/统计行（列数与表头不一致）"
        }

        return ParseResult(format, verdicts, warnings)
    }

    // ------------------------------------------------------------ 判定

    private fun judge(
        format: StatementFormat,
        columnIndex: Map<String, Int>,
        cells: List<String>,
        rowNumber: Int,
    ): RowVerdict {
        val ref = cell(cells, columnIndex, StatementColumns.REF)
        val rawTime = cell(cells, columnIndex, StatementColumns.TIME)
        val rawAmount = cell(cells, columnIndex, StatementColumns.AMOUNT)

        if (ref.isNullOrBlank()) {
            return RowVerdict.Unusable(rowNumber, "缺交易单号——没有它就无法去重与发现退款")
        }
        if (rawTime.isNullOrBlank()) return RowVerdict.Unusable(rowNumber, "缺交易时间")
        if (rawAmount.isNullOrBlank()) return RowVerdict.Unusable(rowNumber, "缺金额")

        val date = parseDate(rawTime, format.timePatterns)
            ?: return RowVerdict.Unusable(rowNumber, "时间无法解析：$rawTime")
        val amount = parseAmount(rawAmount, format)
            ?: return RowVerdict.Unusable(rowNumber, "金额无法解析：$rawAmount")

        val directionRaw = cell(cells, columnIndex, StatementColumns.DIRECTION)
        val direction = parseDirection(directionRaw, format)
            ?: return RowVerdict.Unusable(rowNumber, "收支方向无法识别：$directionRaw")

        val rawType = cell(cells, columnIndex, StatementColumns.TYPE)
        val status = cell(cells, columnIndex, StatementColumns.STATUS)
        val row = StatementRow(
            externalSource = format.id,
            externalRef = ref,
            dateEpochDay = date.toEpochDay(),
            amount = amount,
            direction = direction,
            merchant = cell(cells, columnIndex, StatementColumns.COUNTERPARTY),
            description = cell(cells, columnIndex, StatementColumns.DESCRIPTION),
            status = status,
            rawType = rawType,
            rawTime = rawTime,
            rowNumber = rowNumber,
            isRefund = isRefund(status, rawType, format),
        )

        // 排除的三种理由，按「离钱最近」的顺序判：先看这笔有没有成交，
        // 再看它是不是收支，最后才看类型——顺序反了会把「交易关闭的转账」
        // 报成「转账」，用户会以为只是被当成资金转移，其实它压根没发生。
        if (direction == StatementDirection.NEUTRAL) {
            return RowVerdict.NotConsumption(
                row,
                "收/支 为「${directionRaw?.trim()}」——这是资金转移（充值/提现/还款之类），不是收支",
            )
        }
        skipStatusReason(status, format)?.let { return RowVerdict.NotConsumption(row, it) }
        nonConsumptionReason(rawType, format)?.let { return RowVerdict.NotConsumption(row, it) }

        return RowVerdict.Consumption(row)
    }

    /**
     * 交易状态说明这笔**没有成交**。
     *
     * 最要紧的是支付宝的「交易关闭」：实测 596 笔里有 15 笔，其中 7 笔的收/支列
     * 明写着「支出」。照单全收会让「这个月花了多少」凭空多出这几笔。
     *
     * 注意**不能**把所有非成功状态都排掉：微信的「已全额退款」是成交后又退的，
     * 钱确实动过，将来要靠它发现状态变化并冲减（那是另一件事），所以只排配置里
     * 点名的那几个。
     */
    private fun skipStatusReason(status: String?, format: StatementFormat): String? {
        val value = normalize(status ?: return null)
        if (value.isEmpty()) return null
        val hit = format.skipStatuses.firstOrNull { normalize(it) == value } ?: return null
        return "交易状态「$hit」——这笔没有成交，不是一笔已发生的收支"
    }

    /**
     * 这行的交易类型是不是「资金转移」。
     *
     * 转账、红包、理财存取、信用卡还款都不是消费——混进来会直接让「这个月花了多少」
     * 虚高。判定用**配置里的词表**而不是写死的 if，因为各家的类型名不一样。
     */
    private fun nonConsumptionReason(rawType: String?, format: StatementFormat): String? {
        val type = normalize(rawType ?: return null)
        if (type.isEmpty()) return null
        val hit = format.nonConsumptionTypes.firstOrNull { normalize(it).isNotEmpty() && type.contains(normalize(it)) }
        return hit?.let { "交易类型「$it」属于资金转移，不是消费" }
    }

    /**
     * 这行说的是不是一笔退回来的钱。
     *
     * 只看**状态列**与**交易类型列**，且用配置里的词表。特别地**不看商品说明**：
     * 那是自由文本，商品名里带「退款」二字的正常消费会中招，后果是静默少记一笔支出。
     *
     * 也**不看金额列的负号**——银行流水里普通借方同样常写成 `-38.50`，
     * 靠符号判会把正常支出认成退款。
     */
    private fun isRefund(status: String?, rawType: String?, format: StatementFormat): Boolean {
        if (format.refundMarkers.isEmpty()) return false
        val markers = format.refundMarkers.filter { it.isNotBlank() }.map { normalize(it) }
        if (markers.isEmpty()) return false
        return listOfNotNull(status, rawType).any { value ->
            val normalized = normalize(value)
            markers.any { normalized.contains(it) }
        }
    }

    // ------------------------------------------------------------ 字段解析

    /**
     * 金额一律取**绝对值**——方向由专门的列决定，不靠符号。
     *
     * 账单里两种记法都有（`-38.50` 或 `38.50` + 「支出」），混着用会让同一份文件
     * 里的方向不一致。统一成「绝对值 + 方向列」，符号只在这里剥掉。
     */
    private fun parseAmount(raw: String, format: StatementFormat): Money? {
        var text = raw.trim()
        format.amountStrip.forEach { text = text.replace(it, "") }
        text = text.trim().removePrefix("+").removePrefix("-").trim()
        if (text.isEmpty()) return null
        return runCatching { Money.parse(text, DEFAULT_CURRENCY) }.getOrNull()
    }

    private fun parseDirection(raw: String?, format: StatementFormat): StatementDirection? {
        val value = normalize(raw ?: return null)
        if (value.isEmpty()) return null
        if (format.directionValues[DIRECTION_EXPENSE]?.any { normalize(it) == value } == true) {
            return StatementDirection.EXPENSE
        }
        if (format.directionValues[DIRECTION_INCOME]?.any { normalize(it) == value } == true) {
            return StatementDirection.INCOME
        }
        if (format.directionValues[DIRECTION_NEUTRAL]?.any { normalize(it) == value } == true) {
            return StatementDirection.NEUTRAL
        }
        return null
    }

    private fun parseDate(raw: String, patterns: List<String>): LocalDate? {
        val text = raw.trim()
        val candidates = patterns.ifEmpty { DEFAULT_TIME_PATTERNS }
        for (pattern in candidates) {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
            runCatching { return LocalDate.parse(text, formatter) }
            runCatching { return LocalDateTime.parse(text, formatter).toLocalDate() }
        }
        // 最后兜一手：账单里偶尔混着 ISO 格式
        runCatching { return LocalDate.parse(text) }
        runCatching { return LocalDateTime.parse(text).toLocalDate() }
        return null
    }

    // ------------------------------------------------------------ 表头与列

    /**
     * 表头不在第一行——账单前面通常有若干说明行（导出时间、昵称、「明细列表」分隔线等）。
     * 支付宝实测在第 24 行（index 23），微信 xlsx 在第 18 行。
     *
     * 判定依据是**这一行能对上多少列**，而不是写死几个中文列名：
     * 数据行对不上任何列名，说明行只对得上一两个，只有真表头能对上三列以上。
     * 这样加一家银行或换个列名都不必改这里。
     */
    private fun findHeaderRow(rows: List<List<String>>): Int? =
        rows.indexOfFirst { cells ->
            val normalized = cells.map { normalize(it) }
            formats.any { format -> resolveColumns(format, normalized).size >= HEADER_MIN_MATCHED_COLUMNS }
        }.takeIf { it >= 0 }

    /** 先看表头行里有没有特征词（更强），再看全文——有些账单的平台名只出现在前几行的说明里。 */
    private fun matchFormat(headerLine: String, fullText: String): StatementFormat? {
        val normalizedHeader = normalize(headerLine)
        formats.firstOrNull { f ->
            f.headerMarkers.any { normalizedHeader.contains(normalize(it)) }
        }?.let { return it }
        return formats.firstOrNull { f ->
            f.headerMarkers.any { fullText.contains(it, ignoreCase = true) }
        }
    }

    private fun resolveColumns(format: StatementFormat, headerCells: List<String>): Map<String, Int> {
        val resolved = mutableMapOf<String, Int>()
        for ((logical, aliases) in format.columns) {
            val index = headerCells.indexOfFirst { cell ->
                aliases.any { normalize(it) == cell }
            }.takeIf { it >= 0 }
            if (index != null) resolved[logical] = index
        }
        return resolved
    }

    private fun cell(cells: List<String>, index: Map<String, Int>, logical: String): String? =
        index[logical]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------ 基础工具

    /** 归一化：去空白、去全角字符的宽度差异，让「金额（元）」与「金额(元)」能对上。 */
    internal fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() -> Unit
                ch == '（' -> builder.append('(')
                ch == '）' -> builder.append(')')
                ch == '，' -> builder.append(',')
                ch == '：' -> builder.append(':')
                ch.code in 0xFF01..0xFF5E -> builder.append((ch.code - 0xFEE0).toChar())
                else -> builder.append(ch.lowercaseChar())
            }
        }
        return builder.toString()
    }

    companion object {
        const val DEFAULT_CURRENCY = "CNY"

        private const val DIRECTION_EXPENSE = "expense"
        private const val DIRECTION_INCOME = "income"
        private const val DIRECTION_NEUTRAL = "neutral"

        private val DEFAULT_TIME_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss")

        /**
         * 一行要能对上多少列才算"这行是表头"。
         *
         * 取 3 是因为：说明行最多对上一两个（比如「交易时间：[2026-09-01]」只含一个列名），
         * 数据行一个都对不上，只有真表头能轻松对上三个以上。
         */
        private const val HEADER_MIN_MATCHED_COLUMNS = 3
    }
}
