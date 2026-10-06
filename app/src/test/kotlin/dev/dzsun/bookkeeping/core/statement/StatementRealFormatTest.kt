package dev.dzsun.bookkeeping.core.statement

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实账单格式的**合成夹具**。
 *
 * 形状全部照 `docs/statement-formats-real.md` 与实测（真实导出账单 596 笔 / 89 笔）
 * 复刻，但**没有任何真实数据**——姓名、手机号、商户、单号全是编的。
 * 真实账单只在本机做过一次性验证（见 `StatementRealFileTest`，默认跳过）。
 *
 * 这里刻意把**四个坑各写成一条断言**，而不是只测"能解析"：
 * 编码、回单头、尾逗号、单号带 `\t`——它们单独看都不起眼，但任何一个踩回去
 * 都是整份账单解析失败或金额错位，而错得没有提示。
 */
class StatementRealFormatTest {

    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        assertTrue("找不到格式配置：${file.absolutePath}", file.isFile)
        Json { ignoreUnknownKeys = true }.decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    // ------------------------------------------------------------ 支付宝 CSV

    @Test
    fun `支付宝 GB18030 账单的前 23 行回单头与尾逗号都能跳过`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        assertEquals("应认成支付宝", "alipay", result.format?.id)
        // 表头在第 24 行；前面 23 行里有「交易时间：[…]」这类只对得上一列的说明行，
        // 还有一整行分隔线。表头判据是「能对上三列以上」，不是行号写死
        assertEquals("6 行数据都该被处理", 6, result.verdicts.size)
        assertTrue(
            "不该有解析不了的行：${result.verdicts.filterIsInstance<RowVerdict.Unusable>().map { it.reason }}",
            result.verdicts.none { it is RowVerdict.Unusable },
        )
    }

    @Test
    fun `交易订单号尾部的制表符被 trim 掉`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        val ids = result.consumptionRows.map { it.externalRef }
        assertTrue("单号不该带制表符：$ids", ids.none { it.contains('\t') })
        assertTrue("单号不该带空白：$ids", ids.none { it != it.trim() })
        assertTrue("应真的解析出单号", ids.any { it == "2026010100000001" })
    }

    @Test
    fun `不计收支是第三档，不落账且给出理由`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        val neutral = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
            .first { it.row.merchant == "余额宝" }
        // 关键：它不是"方向无法识别"，而是被认出来并排除——两者的用户含义完全不同
        assertTrue("理由应说明这是资金转移：${neutral.reason}", neutral.reason.contains("不计收支"))
        assertTrue(
            "不计收支的行不该进消费",
            result.consumptionRows.none { it.merchant == "余额宝" },
        )
    }

    @Test
    fun `交易关闭的行不导入——那笔没有成交`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        val closed = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
            .first { it.row.merchant == "关闭的订单" }
        assertTrue("理由应点出交易关闭：${closed.reason}", closed.reason.contains("交易关闭"))
        assertTrue(
            "没成交的支出不该计入",
            result.consumptionRows.none { it.merchant == "关闭的订单" },
        )
    }

    @Test
    fun `支出收入两侧都认，金额不带符号`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        val expense = result.consumptionRows.first { it.merchant == "早餐铺" }
        assertEquals(StatementDirection.EXPENSE, expense.direction)
        assertEquals(600L, expense.amount.amountMinor)
        assertEquals("交易分类应作为交易类型留下来", "餐饮美食", expense.rawType)

        val income = result.consumptionRows.first { it.merchant == "朋友还款" }
        assertEquals(StatementDirection.INCOME, income.direction)
        assertEquals(10_000L, income.amount.amountMinor)

        // 交易分类是「转账红包」的支出要被排除：它是资金转移，不是消费。
        // 实测支付宝账单里 29 笔转账红包全部如此，混进来会让「这个月花了多少」虚高
        assertTrue(result.consumptionRows.none { it.merchant == "转账给朋友" })
    }

    @Test
    fun `商品说明成为明细描述`() {
        val result = CsvStatementParser(formats).parse(alipayCsv().toByteArray(charset("GB18030")))

        // 「买了什么」就靠这一列——流水导入这边天然有，不用模型抽
        assertEquals(
            "哈啰单车卡抵扣骑行费用",
            result.consumptionRows.first { it.merchant == "出行服务" }.description,
        )
    }

    // ------------------------------------------------------------ 微信 xlsx

    @Test
    fun `微信 xlsx 的日期序列号能还原成时间`() {
        val result = XlsxStatementParser(formats).parse(wechatXlsx())

        assertEquals("应认成微信", "wechat", result.format?.id)
        val coffee = result.consumptionRows.first { it.merchant == "咖啡店" }
        // 46301.833865740744 是 Excel 序列号，样式 numFmtId=164 说明它是日期。
        // 不读 styles.xml 就会原样拿到「46301.833865740744」，整列时间全废
        assertEquals("2026-10-06", java.time.LocalDate.ofEpochDay(coffee.dateEpochDay).toString())
    }

    @Test
    fun `微信的第三档是斜杠，不是中性交易三个字`() {
        val result = XlsxStatementParser(formats).parse(wechatXlsx())

        // 实测导出里 收/支 的中性值写的是 `/`（8 笔），文档当时推测成「中性交易」。
        // 这条按**实际取值**盖住，免得下次有人照文档改回去
        val excluded = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
        assertTrue(
            "收/支 为 / 的行应被排除并说明是资金转移：${excluded.map { it.reason }}",
            excluded.any { it.row.merchant == "零钱提现" && it.reason.contains("资金转移") },
        )
        assertTrue(result.consumptionRows.none { it.merchant == "零钱提现" })
    }

    @Test
    fun `微信多列数字金额不会带出浮点噪声`() {
        val result = XlsxStatementParser(formats).parse(wechatXlsx())

        // 金额走的是「原样数字文本 → Money.parse」，全程不经过 Double
        assertEquals(
            4_300L,
            result.consumptionRows.first { it.merchant == "超市" }.amount.amountMinor,
        )
    }

    @Test
    fun `现金额列不是日期格式时不会被误判成日期`() {
        val result = XlsxStatementParser(formats).parse(wechatXlsx())

        // 金额列样式是 ¥#,##0.00（numFmtId=165），含 0 但不含日期占位符。
        // 判定要是只看「有没有样式」就会把金额也当日期，那整列金额全变成 1900 年
        assertTrue(
            "金额不该被当成日期：${result.consumptionRows.map { it.amount.amountMinor }}",
            result.consumptionRows.all { it.amount.amountMinor > 0 },
        )
    }

    @Test
    fun `不是 xlsx 的字节返回认不出而不是崩掉`() {
        val result = XlsxStatementParser(formats).parse("这不是表格".toByteArray())

        assertEquals(null, result.format)
        assertTrue(result.warnings.any { it.contains("xlsx") })
    }

    @Test
    fun `表头之后的短行被当成统计行并计数，不会静默消失`() {
        val result = XlsxStatementParser(formats).parse(wechatXlsx())

        // 表头**之前**的说明行不必告警——它们根本不在遍历范围里，用户也不会以为丢了数据。
        // 但表头**之后**出现的短行必须报数：那多半是账单末尾的统计行，也可能是
        // 一行列数不对的脏数据；后者悄无声息地消失就是丢账。
        assertTrue(
            "表头之后的短行应留下条数：${result.warnings}",
            result.warnings.any { it.contains("说明/统计行") },
        )
        assertEquals("4 行数据都该被判定", 4, result.verdicts.size)
    }

    // ------------------------------------------------------------ 夹具

    /**
     * 支付宝导出账单的合成样本。前 23 行是回单头（导出信息、收支汇总、特别提示、
     * 分隔线），表头带**尾逗号**，单号带 **\t**，收/支有**三档**。
     */
    private fun alipayCsv(): String {
        val preamble = buildString {
            appendLine("支付宝交易明细查询")
            appendLine("账号：[syn***@example.com]")
            appendLine("姓名：[示例用户]")
            appendLine("起始时间：[2026-07-06 00:00:00] 终止时间：[2026-10-06 00:00:00]")
            appendLine("导出交易类型：全部")
            appendLine("收入：20笔 1000.00元")
            appendLine("支出：500笔 8000.00元")
            appendLine("不计收支：34笔 5000.00元")
            // 这些说明行都只对得上一列，正是「表头判据不能只看一列」的原因
            appendLine("特别提示：")
            appendLine("1. 本回单仅作证明使用，不作为收付款凭证。")
            appendLine("2. 交易时间：[2026-07-06 00:00:00]。")
            appendLine("3. 金额单位：元。")
            appendLine("4. 本回单共 2 页。")
            appendLine("5. 电子回单专用章。")
            appendLine("6. 查询流水号：[00000000000000000000]。")
            appendLine("7. 客服电话：95188。")
            appendLine("8. 本回单由系统自动生成。")
            appendLine("9. 如有疑问请联系客服。")
            appendLine("10. 请勿重复提交。")
            appendLine("11. 交易明细以实际为准。")
            appendLine("12. 本页为第 1 页。")
            appendLine("----支付宝支付科技有限公司  电子客户回单----")
            appendLine("以下为交易明细：")
        }
        // 表头：注意**结尾多一个逗号**
        val header = "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式," +
            "交易状态,交易订单号,商家订单号,备注,\n"
        // 数据行：单号与商家单号**尾部各带一个制表符**（实测导出就这样）
        val rows = listOf(
            "2026-07-06 11:48:25,餐饮美食,早餐铺,157******21,豆浆油条,支出,6.00,花呗,交易成功,2026010100000001\t,1783000000000001\t,,",
            "2026-07-07 09:00:00,交通出行,出行服务,157******21,哈啰单车卡抵扣骑行费用,支出,1.50,余额,交易成功,2026010100000002\t,1783000000000002\t,,",
            "2026-07-08 10:00:00,商业服务,朋友还款,157******21,服务费结算,收入,100.00,余额,交易成功,2026010100000003\t,1783000000000003\t,,",
            "2026-07-09 10:00:00,投资理财,余额宝,157******21,余额宝-单次转入,不计收支,2000.00,余额,交易成功,2026010100000004\t,-\t,,",
            "2026-07-10 10:00:00,日用百货,关闭的订单,157******21,抽纸,支出,29.90,花呗,交易关闭,2026010100000005\t,1783000000000005\t,,",
            "2026-07-11 10:00:00,转账红包,转账给朋友,157******21,转账,支出,50.00,余额,交易成功,2026010100000006\t,-\t,,",
        )
        return preamble + header + rows.joinToString("\n") + "\n"
    }

    /**
     * 微信导出 xlsx 的合成样本。
     *
     * 在代码里造而不是塞一个二进制进仓库：一来合成样本能逐字审阅（二进制不能），
     * 二来 `styles.xml` 是这组测试的**重点**（日期与金额靠它区分），
     * 用代码写出来正好把「哪个 numFmtId 是什么」摆在明面上。
     */
    private fun wechatXlsx(): ByteArray {
        // 共享字符串表按用到的值**自动登记**：手写索引的话，加一个值忘了登记
        // 就会在测试里抛 Key is missing——那是夹具的错，不该混进被测代码的失败里
        val strings = mutableListOf<String>()
        fun sid(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings += value
            return strings.size - 1
        }

        fun textCell(ref: String, value: String) =
            """<c r="$ref" s="2" t="s"><v>${sid(value)}</v></c>"""

        // 时间列用 s="1"（numFmtId=164 = yyyy-mm-dd hh:mm:ss）且**不带 t 属性**——
        // 这正是实测的形状：日期在 xlsx 里就是个数字，靠样式才知道是日期
        fun dateCell(ref: String, serial: String) = """<c r="$ref" s="1"><v>$serial</v></c>"""
        // 金额列 s="2"（numFmtId=165 = ¥#,##0.00），是数字但**不是**日期
        fun moneyCell(ref: String, value: String) = """<c r="$ref" s="2"><v>$value</v></c>"""

        val header = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
            "支付方式", "当前状态", "交易单号", "商户单号", "备注")
            .mapIndexed { i, name -> textCell(colName(i) + "18", name) }.joinToString("")

        val rows = listOf(
            // 1) 正常支出；46301.833865740744 = 2026-10-06
            dateCell("A19", "46301.833865740744") +
                textCell("B19", "商户消费") + textCell("C19", "咖啡店") + textCell("D19", "拿铁 大杯") +
                textCell("E19", "支出") + moneyCell("F19", "38.5") + textCell("G19", "零钱") +
                textCell("H19", "支付成功") + textCell("I19", "WX0000000000000001") +
                textCell("J19", "-") + textCell("K19", "备注一"),
            // 2) 中性交易：收/支 是 `/`，不是「中性交易」
            dateCell("A20", "46302.5") +
                textCell("B20", "零钱提现") + textCell("C20", "零钱提现") + textCell("D20", "提现") +
                textCell("E20", "/") + moneyCell("F20", "500") + textCell("G20", "零钱") +
                textCell("H20", "提现已到账") + textCell("I20", "WX0000000000000002") +
                textCell("J20", "-") + textCell("K20", "备注二"),
            // 3) 资金转移：转账
            dateCell("A21", "46303.25") +
                textCell("B21", "转账") + textCell("C21", "朋友") + textCell("D21", "转账") +
                textCell("E21", "支出") + moneyCell("F21", "100") + textCell("G21", "零钱") +
                textCell("H21", "已转账") + textCell("I21", "WX0000000000000003") +
                textCell("J21", "-") + textCell("K21", "备注三"),
            // 4) 备注为空——xlsx 里表现为**自闭合的空单元格**，而不是少一列。
            //    真实导出 89 行全部 11 列齐全，空的也占位
            dateCell("A22", "46304.1") +
                textCell("B22", "商户消费") + textCell("C22", "超市") + textCell("D22", "日用品") +
                textCell("E22", "支出") + moneyCell("F22", "43") + textCell("G22", "银行卡") +
                textCell("H22", "支付成功") + textCell("I22", "WX0000000000000004") +
                textCell("J22", "-") + """<c r="K22" s="2"/>""",
        )

        val sheet = StringBuilder()
            .append("""<?xml version="1.0" encoding="UTF-8"?>""")
            .append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        // 前 17 行是导出说明——表头在第 18 行，正是「表头不在第一行」那个坑
        listOf(
            "微信支付账单明细", "微信昵称：[示例用户]", "起始时间：[2026-07-06]",
            "导出类型：全部", "共 4 笔记录", "以下为明细：", "说明一", "说明二",
            "说明三", "说明四", "说明五", "说明六", "说明七", "说明八",
            "说明九", "------微信支付账单明细列表------", "说明十",
        ).forEachIndexed { i, value ->
            sheet.append("""<row r="${i + 1}">""").append(textCell("A${i + 1}", value)).append("</row>")
        }
        sheet.append("""<row r="18">$header</row>""")
        rows.forEachIndexed { i, body -> sheet.append("""<row r="${19 + i}">$body</row>""") }
        // 表头之后的一行统计行，只有 A 列
        sheet.append("""<row r="23">""").append(textCell("A23", "共 4 笔记录")).append("</row>")
        sheet.append("</sheetData></worksheet>")

        return zipOf(
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook/>""",
            "xl/sharedStrings.xml" to buildString {
                append("""<?xml version="1.0"?><sst count="${strings.size}">""")
                strings.forEach { append("<si><t>").append(it).append("</t></si>") }
                append("</sst>")
            },
            // numFmtId 164 = 日期，165 = 货币。#164 必须被认成日期、#165 必须不被认成日期
            "xl/styles.xml" to """<?xml version="1.0"?><styleSheet>
                <numFmts count="2">
                  <numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/>
                  <numFmt numFmtId="165" formatCode="¥#,##0.00"/>
                </numFmts>
                <cellXfs count="3">
                  <xf numFmtId="0"/><xf numFmtId="164"/><xf numFmtId="165"/>
                </cellXfs>
                </styleSheet>""".trimIndent(),
            "xl/worksheets/sheet1.xml" to sheet.toString(),
        )
    }

    /** 0 → A、1 → B……xlsx 的列名。 */
    private fun colName(index: Int): String {
        var remaining = index
        val name = StringBuilder()
        while (true) {
            name.insert(0, ('A' + remaining % 26))
            remaining = remaining / 26 - 1
            if (remaining < 0) break
        }
        return name.toString()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
