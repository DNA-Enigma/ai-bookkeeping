package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.money.Money
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把流水文件（CSV 字节）解析成归一化的行。
 *
 * **纯 JVM**，不依赖 Android——解析是这块最需要反复试错的部分，放在纯 JVM 上能用
 * 单元测试快速覆盖，不必每次上模拟器。
 *
 * 刻意不碰模型：解析只负责把字节变成 [StatementRow]，「是不是消费」「要不要去重」
 * 都是之后的事。混在一起会让每条规则都难以单独验证。
 */
class CsvStatementParser(private val formats: List<StatementFormat>) {

    fun parse(bytes: ByteArray): ParseResult {
        val text = decode(bytes)
        val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()

        val headerIndex = findHeaderLine(lines)
            ?: return ParseResult(
                format = null,
                verdicts = emptyList(),
                warnings = listOf("找不到表头行：这份文件可能不是账单，或格式尚未支持"),
            )

        val headerCells = splitCsvLine(lines[headerIndex]).map { normalize(it) }
        val headerLine = lines[headerIndex]

        // 先按表头行认格式，认不出再用全文的特征词兜底——
        // 有些账单表头里没有平台名，只有正文或前几行的说明里才有
        val format = matchFormat(headerLine, text)
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

        for (index in headerIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val cells = splitCsvLine(line)
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
        val row = StatementRow(
            externalSource = format.id,
            externalRef = ref,
            dateEpochDay = date.toEpochDay(),
            amount = amount,
            direction = direction,
            merchant = cell(cells, columnIndex, StatementColumns.COUNTERPARTY),
            description = cell(cells, columnIndex, StatementColumns.DESCRIPTION),
            status = cell(cells, columnIndex, StatementColumns.STATUS),
            rawType = rawType,
            rawTime = rawTime,
            rowNumber = rowNumber,
        )

        val transferReason = nonConsumptionReason(rawType, format)
        return if (transferReason != null) {
            RowVerdict.NotConsumption(row, transferReason)
        } else {
            RowVerdict.Consumption(row)
        }
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
     *
     * 判定依据是**这一行能对上多少列**，而不是写死几个中文列名：
     * 数据行对不上任何列名，说明行只对得上一两个，只有真表头能对上三列以上。
     * 这样加一家银行或换个列名都不必改这里。
     */
    private fun findHeaderLine(lines: List<String>): Int? =
        lines.indexOfFirst { line ->
            val cells = splitCsvLine(line).map { normalize(it) }
            formats.any { format -> resolveColumns(format, cells).size >= HEADER_MIN_MATCHED_COLUMNS }
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

    /**
     * 编码试用。BOM 优先，否则严格试 UTF-8——解不出来才退 GBK。
     *
     * 顺序不能反：GBK 几乎能"成功"解码任意字节（中文乱码而不会报错），
     * 先试 GBK 会把正常的 UTF-8 文件解成乱码且毫无提示。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        for (name in FALLBACK_ENCODINGS) {
            runCatching { strictDecode(bytes, Charset.forName(name)) }.getOrNull()?.let { return it }
        }
        // 全试不通就带替换字符解出来，让用户至少看得到内容以便反馈
        return String(bytes, Charsets.UTF_8)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * 按 CSV 规则切一行：引号内的逗号不算分隔符，两个连续引号表示一个引号。
     *
     * 不用 `split(",")`——商品名里带逗号是常态（「拿铁,大杯」），
     * 那会让整行错位，而且错得很安静。
     */
    internal fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' -> {
                    if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"'); index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    cells += current.toString(); current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        cells += current.toString()
        return cells
    }

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

        private val FALLBACK_ENCODINGS = listOf("GBK", "GB18030", "UTF-16LE")
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
