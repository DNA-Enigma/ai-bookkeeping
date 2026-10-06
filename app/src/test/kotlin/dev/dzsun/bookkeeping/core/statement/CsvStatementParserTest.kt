package dev.dzsun.bookkeeping.core.statement

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvStatementParserTest {

    /**
     * 刻意读**真实的** assets 配置，而不是在测试里另造一份格式定义。
     * 另造一份的话，配置写错了测试照样绿——而配置正是这功能最常出错的地方。
     */
    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        assertTrue("找不到格式配置：${file.absolutePath}", file.isFile)
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    private val parser = CsvStatementParser(formats)

    // ------------------------------------------------------------ 正常路径

    @Test
    fun `解析微信账单并归一化字段`() {
        val result = parser.parse(wechatCsv().toByteArray())

        assertEquals("wechat", result.format?.id)
        val rows = result.consumptionRows
        assertEquals(2, rows.size)

        val coffee = rows.first { it.externalRef.endsWith("1234567890") }
        assertEquals(38_50L, coffee.amount.amountMinor)
        assertEquals(StatementDirection.EXPENSE, coffee.direction)
        assertEquals("星巴克咖啡", coffee.merchant)
        assertEquals("拿铁,大杯", coffee.description)
        assertEquals("支付成功", coffee.status)
        assertEquals("2026-09-15", java.time.LocalDate.ofEpochDay(coffee.dateEpochDay).toString())
    }

    @Test
    fun `表头不在第一行也能找到`() {
        // 账单前面有导出时间、昵称等说明行，这是常态
        val csv = wechatCsv()
        assertEquals(2, parser.parse(csv.toByteArray()).consumptionRows.size)
    }

    @Test
    fun `引号内的逗号不算分隔符`() {
        // 「拿铁,大杯」若被 split(",") 切开，整行会错位——而且错得很安静
        val result = parser.parse(wechatCsv().toByteArray())
        val coffee = result.consumptionRows.first { it.description?.contains("拿铁") == true }
        assertEquals("拿铁,大杯", coffee.description)
    }

    @Test
    fun `金额剥离货币符号与千分位`() {
        val result = parser.parse(wechatCsv().toByteArray())
        val big = result.consumptionRows.first { it.merchant == "永辉超市" }
        assertEquals(123_456L, big.amount.amountMinor)   // ￥1,234.56
    }

    @Test
    fun `半角货币符号也能剥离`() {
        // 真实账单多用全角￥，但别赌这个——半角的 ¥ 也剥得掉才稳
        val csv = wechatCsv().replace('￥', '¥')
        val result = parser.parse(csv.toByteArray())
        val coffee = result.consumptionRows.first { it.merchant == "星巴克咖啡" }
        assertEquals(38_50L, coffee.amount.amountMinor)
    }

    @Test
    fun `金额符号被剥掉，方向以专门的列为准`() {
        val csv = wechatCsv().replace("￥38.50", "-38.50")
        val result = parser.parse(csv.toByteArray())
        val coffee = result.consumptionRows.first { it.merchant == "星巴克咖啡" }
        assertEquals(38_50L, coffee.amount.amountMinor)
        assertEquals(StatementDirection.EXPENSE, coffee.direction)
    }

    // ------------------------------------------------------------ 排除与告警

    @Test
    fun `转账不算消费且给出理由`() {
        val result = parser.parse(wechatCsv().toByteArray())
        val excluded = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
        assertEquals(1, excluded.size)
        assertTrue(
            "理由应说明为什么排除，实际：${excluded.first().reason}",
            excluded.first().reason.contains("转账"),
        )
        assertTrue(result.consumptionRows.none { it.rawType == "转账" })
    }

    @Test
    fun `缺交易单号的行不可用且说明原因`() {
        // 没有单号就无法去重、也无法发现退款，不能凑合入账
        val csv = wechatCsv().replace("4200002319202609151234567890", "")
        val result = parser.parse(csv.toByteArray())
        val unusable = result.verdicts.filterIsInstance<RowVerdict.Unusable>()
        assertTrue(unusable.any { it.reason.contains("交易单号") })
    }

    @Test
    fun `末尾统计行被静默跳过而不是当成错行`() {
        val result = parser.parse(wechatCsv().toByteArray())
        // 「共 2 笔记录」列数与表头不符
        assertTrue(result.warnings.any { it.contains("说明/统计行") })
        assertTrue(result.verdicts.none { it is RowVerdict.Unusable && it.rowNumber > 10 })
    }

    // ------------------------------------------------------------ 编码

    @Test
    fun `GBK 编码的文件能正确解码`() {
        val bytes = wechatCsv().toByteArray(charset("GBK"))
        val result = parser.parse(bytes)
        assertEquals("wechat", result.format?.id)
        assertTrue(
            "GBK 解码后商户名应正常：${result.consumptionRows.map { it.merchant }}",
            result.consumptionRows.any { it.merchant == "星巴克咖啡" },
        )
    }

    @Test
    fun `UTF-8 带 BOM 的文件能正确解码`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val result = parser.parse(bom + wechatCsv().toByteArray())
        assertEquals("wechat", result.format?.id)
        assertTrue(result.consumptionRows.any { it.merchant == "星巴克咖啡" })
    }

    @Test
    fun `正常 UTF-8 文件不会被误判成 GBK`() {
        // GBK 几乎能"成功"解码任意字节，先试它会把中文解成乱码且毫无提示
        val result = parser.parse(wechatCsv().toByteArray(Charsets.UTF_8))
        assertTrue(result.consumptionRows.none { it.merchant?.contains("锟") == true })
    }

    // ------------------------------------------------------------ 认不出来的情况

    @Test
    fun `认不出格式时返回 null 并给出可读告警`() {
        val result = parser.parse("姓名,年龄\n张三,30\n".toByteArray())
        assertNull(result.format)
        assertTrue(result.consumptionRows.isEmpty())
        assertTrue("应给出可读告警", result.warnings.any { it.contains("找不到表头行") })
    }

    @Test
    fun `表头缺必需列时说清楚缺了什么`() {
        val csv = wechatCsv().replace("金额(元)", "数额")
        val result = parser.parse(csv.toByteArray())
        assertTrue(
            "应指出缺哪一列：${result.warnings}",
            result.warnings.any { it.contains("缺少必需列") && it.contains("amount") },
        )
    }

    @Test
    fun `全角括号的列名也能对上`() {
        val csv = wechatCsv().replace("金额(元)", "金额（元）")
        val result = parser.parse(csv.toByteArray())
        assertEquals(2, result.consumptionRows.size)
    }

    // ------------------------------------------------------------ 夹具

    private fun wechatCsv(): String = """
        微信支付账单明细
        微信昵称：[张三]
        起始时间：[2026-09-01 00:00:00] 终止时间：[2026-10-01 00:00:00]
        ----------------------微信支付账单明细列表--------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号
        2026-09-15 08:12:33,商户消费,星巴克咖啡,"拿铁,大杯",支出,￥38.50,零钱,支付成功,4200002319202609151234567890,SBK20260915081233
        2026-09-16 12:30:00,转账,李四,-,支出,￥100.00,零钱,已转账,4200002319202609160000000001,-
        2026-09-17 20:00:00,商户消费,永辉超市,日用品,支出,"￥1,234.56",银行卡,支付成功,4200002319202609170000000002,-
        共 3 笔记录
    """.trimIndent()
}
