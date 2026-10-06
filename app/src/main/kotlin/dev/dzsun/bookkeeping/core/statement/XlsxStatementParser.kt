package dev.dzsun.bookkeeping.core.statement

/**
 * 把 xlsx 账单（微信实测就是这种）解析成归一化的行。
 *
 * 与 [CsvStatementParser] 的区别**只在前一步**：怎么把文件变成单元格网格。
 * 找表头、认格式、判收支、排除非消费全部复用 [StatementRowParser]——
 * 两边各写一份的话，改了 CSV 那条忘了改 xlsx 这条，同一份账单换个格式导入
 * 结果就不一样了，而这种不一致极难被发现。
 *
 * 读不出 xlsx 时返回 `format = null`，调用方据此退回按压缩包处理
 * （微信早期版本导出的是**加密 zip + csv**，那条路还在）。
 */
class XlsxStatementParser(private val formats: List<StatementFormat>) {

    private val rows = StatementRowParser(formats)

    fun parse(bytes: ByteArray): ParseResult {
        val grid = XlsxSheet.read(bytes)
            ?: return ParseResult(
                format = null,
                verdicts = emptyList(),
                warnings = listOf("不是可读的 xlsx（可能不是表格文件，或是加密压缩包）"),
            )
        // 认格式的兜底要全文：有些账单的平台名只出现在前几行的说明里，
        // 那些说明通常只占 A 列，拼起来就是全文
        val fullText = grid.joinToString("\n") { it.joinToString(" ") }
        return rows.parse(grid, fullText)
    }
}
