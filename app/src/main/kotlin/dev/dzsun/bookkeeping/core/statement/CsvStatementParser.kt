package dev.dzsun.bookkeeping.core.statement

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 把流水文件（CSV 字节）解析成归一化的行。
 *
 * **纯 JVM**，不依赖 Android——解析是这块最需要反复试错的部分，放在纯 JVM 上能用
 * 单元测试快速覆盖，不必每次上模拟器。
 *
 * 这里面只管 CSV 特有的两件事：**解码**与**切单元格**。找表头、认格式、判收支
 * 那些与格式无关的判定在 [StatementRowParser] 里，和 xlsx 共用。
 *
 * 刻意不碰模型：解析只负责把字节变成 [StatementRow]，「是不是消费」「要不要去重」
 * 都是之后的事。混在一起会让每条规则都难以单独验证。
 */
class CsvStatementParser(private val formats: List<StatementFormat>) {

    private val rows = StatementRowParser(formats)

    fun parse(bytes: ByteArray): ParseResult {
        val text = decode(bytes)
        // 行与文件行号一一对应（哪怕某行会被跳过），rowNumber 才对得上用户手里的文件
        val cells = text.lineSequence().map { splitCsvLine(it.trimEnd('\r')) }.toList()
        return rows.parse(cells, text)
    }

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

    /**
     * 编码试用。BOM 优先，否则严格试 UTF-8——解不出来才退中文编码。
     *
     * 顺序不能反：GB18030/GBK 几乎能"成功"解码任意字节（中文乱码而不会报错），
     * 先试它会把正常的 UTF-8 文件解成乱码且毫无提示。
     *
     * 而 GBK 与 GB18030 之间的顺序**只能取 GB18030**：GB18030 是 GBK 的严格超集，
     * 能解的它都能解，反过来不成立。支付宝实测导出的就是 GB18030 能解、
     * 按 UTF-8 解直接抛 `UnicodeDecodeError` 的那种。两个都列只会让后者永远轮不到，
     * 是死配置。
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

    companion object {
        private val FALLBACK_ENCODINGS = listOf("GB18030", "UTF-16LE")
    }
}
