package dev.dzsun.bookkeeping.core.statement

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 从 xlsx 里读出第一个工作表的单元格网格。
 *
 * **为什么自己读而不是引库**：xlsx 就是一个 zip + 几份 XML，而账单导出只有
 * 「一张表、全是字符串」这一种形态。为此引 Apache POI（含传递依赖好几 MB，
 * 还带 Android 上没有的类）不值当；引轻量库也一样要动
 * `gradle/libs.versions.toml`——那是**界面同事正在改的共用文件**，为一个
 * 15KB 的文件去制造一次冲突更不值当。
 *
 * 已知不做的事（都写在这里，免得下一个人以为它能读任意 xlsx）：
 * - 只读**第一个工作表**。账单导出就一张表。
 * - 不处理公式求值，只取 `<v>` 里缓存的结果。
 *
 * **日期要读 `styles.xml` 才认得出来。** Excel 里日期就是一个数（序列号 46301.83
 * 表示 2026-10-06 20:00），是不是日期完全由单元格的**数字格式**决定——同一个
 * 46301 配上货币格式就是「¥46,301.00」。微信账单的交易时间列正是这种：
 * 单元格没有 `t` 属性（默认按数字存），靠 `s="1"` 指向 `numFmtId=164`
 * 这个自定义格式 `yyyy-mm-dd hh:mm:ss` 才知道是时间。
 * 不读样式就会把整列时间解析成「46301.833865740744」，而那是**每一行都失败**。
 */
internal object XlsxSheet {

    private const val WORKBOOK_MARKER = "xl/workbook.xml"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val STYLES = "xl/styles.xml"
    private const val SHEET_PREFIX = "xl/worksheets/sheet"
    private const val ENTRY_BUFFER = 16 * 1024

    /** 列名相乘的溢出哨兵，远大于 [StatementLimits.MAX_COLUMNS]，仅用于提前收手。 */
    private const val MAX_COLUMN_GUARD = 1_000_000

    /**
     * 读成网格。[row][col] 是单元格文本，且**行下标与 Excel 的行号一一对应**
     * （第 1 行在 index 0）——行号要拿去给用户看，不能因为空行就错位。
     *
     * 不是可读的 xlsx 时返回 null（加密 zip、普通 CSV、别的 zip 都走这条），
     * 让调用方退回按普通压缩包处理。
     */
    fun read(bytes: ByteArray): List<List<String>>? {
        if (bytes.size > StatementLimits.MAX_ARCHIVE_BYTES) {
            throw StatementLimitExceeded(
                "文件过大（${StatementLimits.humanSize(bytes.size.toLong())}），" +
                    "超过上限 ${StatementLimits.humanSize(StatementLimits.MAX_ARCHIVE_BYTES.toLong())}；已拒绝导入",
            )
        }
        val entries = unzip(bytes) ?: return null
        if (!entries.containsKey(WORKBOOK_MARKER)) return null

        val sheetXml = (entries[SHEET_PREFIX + "1.xml"]?.let { it } ?: entries.entries
            .filter { it.key.startsWith(SHEET_PREFIX) }
            .minByOrNull { it.key }
            ?.value) ?: return null

        val shared = entries[SHARED_STRINGS]?.let { parseSharedStrings(it) } ?: emptyList()
        val dateStyles = entries[STYLES]?.let { parseDateStyles(it) } ?: emptySet()
        return parseSheet(sheetXml, shared, dateStyles)
    }

    /**
     * 解压所有条目，**带炸弹防护**。
     *
     * 与 [StatementArchive] 是两条独立入口（xlsx 也是 zip，会先走这里），
     * 所以上限两边都要守。头部声明的大小不可信，真正的兜底是
     * [readEntryLimited] 按**实际读取量**累计。
     *
     * 不是可读的 zip（加密包、别的压缩格式）返回 null，让调用方退回按压缩包处理；
     * 只有**触碰上限**才抛 [StatementLimitExceeded]——那必须让用户看见理由，不能静默吞掉。
     */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray>? {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++count > StatementLimits.MAX_ENTRIES) {
                        throw StatementLimitExceeded("压缩包条目数超过 ${StatementLimits.MAX_ENTRIES}；已拒绝导入")
                    }
                    StatementLimits.checkRatio(entry.compressedSize, entry.size, "压缩包内的「${entry.name}」")
                    // ZipInputStream.read 在当前条目结束处返回 -1，所以这读到的是**这一个条目**
                    val data = zip.readEntryLimited(StatementLimits.MAX_ENTRY_BYTES)
                    total += data.size
                    if (total > StatementLimits.MAX_TOTAL_BYTES) {
                        throw StatementLimitExceeded(
                            "压缩包解压后累计超过 " +
                                "${StatementLimits.humanSize(StatementLimits.MAX_TOTAL_BYTES.toLong())}；疑似压缩炸弹，已拒绝导入",
                        )
                    }
                    entries[entry.name] = data
                }
            }
        } catch (e: java.util.zip.ZipException) {
            return null
        } catch (e: java.io.IOException) {
            return null
        }
        return entries
    }

    /** 读一个条目的全部字节，超过 [limit] 立即失败——绝不把未知长度的流读爆内存。 */
    private fun ZipInputStream.readEntryLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(ENTRY_BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size().toLong() + read > limit) {
                throw StatementLimitExceeded(
                    "压缩包内的条目解压后超过 ${StatementLimits.humanSize(limit.toLong())}；疑似压缩炸弹，已拒绝导入",
                )
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 共享字符串表。一条 `<si>` 可能被拆成多个 `<r>`（富文本分片），
     * 所以要**把所有 `<t>` 拼起来**，只取第一个会静默截断文本。
     */
    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val doc = parse(xml) ?: return emptyList()
        val items = doc.getElementsByTagName("si")
        return (0 until items.length).map { index ->
            textOf(items.item(index) as? Element)
        }
    }

    private fun parseSheet(
        xml: ByteArray,
        shared: List<String>,
        dateStyles: Set<Int>,
    ): List<List<String>>? {
        val doc = parse(xml) ?: return null
        val rowNodes = doc.getElementsByTagName("row")
        if (rowNodes.length == 0) return null

        val rows = mutableListOf<List<String>>()
        for (i in 0 until rowNodes.length) {
            val rowElement = rowNodes.item(i) as? Element ?: continue
            // row 的 r 属性是 1 基的 Excel 行号，缺了就按顺序递推。
            // 小于 1 的（0/负数）是畸形值，同样按递推处理，别拿去当数组下标。
            val declaredRow = rowElement.getAttribute("r").toIntOrNull()
            val rowNumber = declaredRow?.takeIf { it >= 1 } ?: (rows.size + 1)
            // 行号是**不可信输入**：`r="2000000000"` 会让下面的循环建出二十亿个空行
            if (rowNumber > StatementLimits.MAX_ROWS) {
                throw StatementLimitExceeded(
                    "表格行号 $rowNumber 超过上限 ${StatementLimits.MAX_ROWS}；已拒绝导入",
                )
            }
            while (rows.size < rowNumber) rows.add(emptyList())

            val cells = mutableListOf<String>()
            val cellNodes = rowElement.getElementsByTagName("c")
            for (j in 0 until cellNodes.length) {
                val cell = cellNodes.item(j) as? Element ?: continue
                val rawColumn = columnIndex(cell.getAttribute("r"))
                // 列号同理：超长列名（如 `ZZZZZZZZZ1`）会撑出巨大的列数组
                if (rawColumn >= StatementLimits.MAX_COLUMNS) {
                    throw StatementLimitExceeded(
                        "表格列号超过上限 ${StatementLimits.MAX_COLUMNS}；已拒绝导入",
                    )
                }
                val column = rawColumn.coerceAtLeast(cells.size)
                while (cells.size <= column) cells.add("")
                cells[column] = cellText(cell, shared, dateStyles)
            }
            rows[rowNumber - 1] = cells
        }
        return rows
    }

    /**
     * 单元格取值。四种存法都要认：
     * - `t="s"` 共享字符串（账单里的文字与说明都是这种）
     * - `t="inlineStr"` 内联字符串
     * - `t="str"` 公式的字符串结果
     * - 无 `t` 或 `t="n"` 数字——**这时才要看样式**：样式是日期格式就按 Excel
     *   序列号还原成时间，否则原样输出数字文本
     */
    private fun cellText(cell: Element, shared: List<String>, dateStyles: Set<Int>): String {
        val type = cell.getAttribute("t")
        val value = firstChildText(cell, "v")
        return when (type) {
            "s" -> value?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr" -> textOf(cell)
            "str" -> value ?: textOf(cell)
            else -> {
                val raw = value ?: return ""
                val styleIndex = cell.getAttribute("s").toIntOrNull()
                if (styleIndex != null && styleIndex in dateStyles) {
                    serialToText(raw) ?: raw
                } else {
                    raw
                }
            }
        }
    }

    /**
     * Excel 日期序列号 → `yyyy-MM-dd HH:mm:ss`。
     *
     * 基准取 **1899-12-30** 而不是 1900-01-01：Excel 认为 1900 是闰年（当年 Lotus
     * 1-2-3 的兼容包袱），序列号 60 对应那个不存在的 1900-02-29。用 12-30 做基准
     * 能让 1900-03-01 之后的日期全部对上，而账单不可能早于那天。
     *
     * 输出成字符串而不是 `LocalDate`，是为了让下游（[StatementRowParser]）
     * 只面对一种东西：文本。日期的解析规则与格式配置因此只有一处。
     */
    private fun serialToText(raw: String): String? {
        val serial = raw.trim().toDoubleOrNull() ?: return null
        // 负数列是纯文本或异常值，别硬转
        if (serial < 0) return null
        val days = kotlin.math.floor(serial).toLong()
        val secondsInDay = Math.round((serial - days) * SECONDS_PER_DAY)
        val moment = LocalDateTime.of(EXCEL_EPOCH.plusDays(days), LocalTime.MIDNIGHT)
            .plusSeconds(secondsInDay)
        return moment.format(SERIAL_FORMAT)
    }

    /**
     * 哪些样式索引是「日期」，以及自定义格式码。
     *
     * 判定分两步：内置格式号落在日期区间（14–22 是日期时间，27–36 / 50–58 是
     * 东亚历法日期，45–47 是时间），或自定义格式码里含日期占位符。
     */
    private fun parseDateStyles(xml: ByteArray): Set<Int> {
        val doc = parse(xml) ?: return emptySet()

        val customFormats = mutableMapOf<Int, String>()
        val numFmtNodes = doc.getElementsByTagName("numFmt")
        for (i in 0 until numFmtNodes.length) {
            val node = numFmtNodes.item(i) as? Element ?: continue
            val id = node.getAttribute("numFmtId").toIntOrNull() ?: continue
            customFormats[id] = node.getAttribute("formatCode")
        }

        // cellXfs 的**顺序**就是单元格 s 属性引用的索引，所以只能按下标读
        val cellXfs = doc.getElementsByTagName("cellXfs").item(0) as? Element ?: return emptySet()
        val xfNodes = cellXfs.getElementsByTagName("xf")

        val dateStyles = mutableSetOf<Int>()
        for (i in 0 until xfNodes.length) {
            val xf = xfNodes.item(i) as? Element ?: continue
            val numFmtId = xf.getAttribute("numFmtId").toIntOrNull() ?: continue
            val isDate = numFmtId in BUILTIN_DATE_FORMATS ||
                customFormats[numFmtId]?.let { hasDatePlaceholder(it) } == true
            if (isDate) dateStyles += i
        }
        return dateStyles
    }

    /**
     * 格式码里有没有日期占位符。
     *
     * 要先剥掉引号里的字面量与方括号段（`[Red]`、`[$-409]`、`[h]` 这类），
     * 否则 `"年"0.00` 会被当成日期、而 `[$-409]` 里的 h 也会。
     */
    private fun hasDatePlaceholder(formatCode: String): Boolean {
        val cleaned = formatCode
            .replace(LITERAL, "")
            .replace(BRACKET, "")
        return DATE_PLACEHOLDER.containsMatchIn(cleaned)
    }

    /** `A` → 0，`B` → 1，`AA` → 26。`r` 形如 `B12`，取字母部分。 */
    private fun columnIndex(reference: String): Int {
        var index = 0
        for (ch in reference) {
            if (ch !in 'A'..'Z') break
            index = index * 26 + (ch - 'A' + 1)
            // 提前收手，避免超长列名把 Int 乘溢出成负数——
            // 负数会被 coerceAtLeast 当成"没这列"，静默丢数据。返回一个明显越界的哨兵。
            if (index > MAX_COLUMN_GUARD) return Int.MAX_VALUE
        }
        return index - 1
    }

    private fun textOf(element: Element?): String {
        val node = element ?: return ""
        val texts = node.getElementsByTagName("t")
        return (0 until texts.length).joinToString("") { texts.item(it).textContent.orEmpty() }
    }

    private fun firstChildText(element: Element, tag: String): String? {
        val found = element.getElementsByTagName(tag)
        if (found.length == 0) return null
        return found.item(0).textContent
    }

    /**
     * 解析 XML。**关掉外部实体**——账单是用户从邮箱里拿来的文件，
     * 不该让它的 XML 有机会去读本机文件（XXE）。
     */
    private fun parse(xml: ByteArray): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }.getOrNull()

    /** 1899-12-30 见 [serialToText] 的说明。 */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val SERIAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val SECONDS_PER_DAY = 86_400.0

    /** `"字面量"` 段。 */
    private val LITERAL = Regex("\"[^\"]*\"")
    /** `[Red]` `[$-409]` 段。 */
    private val BRACKET = Regex("\\[[^\\]]*\\]")
    private val DATE_PLACEHOLDER = Regex("[ymdhs]", RegexOption.IGNORE_CASE)

    /**
     * 内置的日期/时间格式号（ECMA-376 §18.8.30）。
     * 14–22 日期与时间，27–36 与 50–58 东亚历法日期，45–47 时间。
     */
    private val BUILTIN_DATE_FORMATS: Set<Int> =
        setOf(14, 15, 16, 17, 18, 19, 20, 21, 22) +
            (27..36).toSet() +
            setOf(45, 46, 47) +
            (50..58).toSet()
}
