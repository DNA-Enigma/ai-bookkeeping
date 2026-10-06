package dev.dzsun.bookkeeping.core.statement

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **对着真实导出账单**验一遍解析器。
 *
 * 默认跳过，且**只从环境变量取路径**——真实账单含姓名、手机号、完整交易记录，
 * 路径与内容都不进仓库。手动跑：
 *
 * ```
 * STATEMENT_REAL_ALIPAY="/path/to/支付宝交易明细.csv" \
 * STATEMENT_REAL_WECHAT="/path/to/微信支付账单.xlsx" \
 *   tools/build.sh :app:testDebugUnitTest --tests "*StatementRealFileTest*" --rerun-tasks
 * ```
 *
 * 断言的是**不变式**而不是具体数字：账单换一份、笔数变了，这些仍然成立；
 * 而四个坑（编码、回单头、尾逗号、单号带 \t）里任何一个踩回去，都会让
 * 「每一行都能解析」这条立刻变红。具体数字由 `println` 输出供人核对，
 * 因为把真实账单的金额写进断言就等于把隐私写进仓库。
 */
class StatementRealFileTest {

    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        assertTrue("找不到格式配置：${file.absolutePath}", file.isFile)
        Json { ignoreUnknownKeys = true }.decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    @Test
    fun `真实支付宝 CSV 每一行都能解析`() {
        val path = System.getenv("STATEMENT_REAL_ALIPAY")
        assumeTrue("未设 STATEMENT_REAL_ALIPAY，跳过", path != null)
        val file = File(path!!)
        assumeTrue("账单不存在：$path", file.isFile)

        val result = CsvStatementParser(formats).parse(file.readBytes())
        report("支付宝", result)

        assertEquals("应认成支付宝", "alipay", result.format?.id)

        // 这条是四个坑的总闸：编码错了、回单头没跳过、尾逗号把列错位了、
        // 单号里的 \t 没 trim 掉——任何一种都会让行落进 Unusable
        val unusable = result.verdicts.filterIsInstance<RowVerdict.Unusable>()
        assertTrue("不该有解析不了的行，实际：${unusable.take(5).map { it.reason }}", unusable.isEmpty())

        // 三档收支都要被认出来，而不是把「不计收支」当成方向无法识别
        val excluded = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
        assertTrue(
            "应识别出「不计收支」这一档并排除",
            excluded.any { it.reason.contains("不计收支") },
        )
        assertTrue(
            "应排除「交易关闭」的行——那笔没有成交",
            excluded.any { it.reason.contains("交易关闭") },
        )
        assertTrue("应解析出消费行", result.consumptionRows.isNotEmpty())
    }

    @Test
    fun `真实微信 xlsx 每一行都能解析`() {
        val path = System.getenv("STATEMENT_REAL_WECHAT")
        assumeTrue("未设 STATEMENT_REAL_WECHAT，跳过", path != null)
        val file = File(path!!)
        assumeTrue("账单不存在：$path", file.isFile)

        val result = XlsxStatementParser(formats).parse(file.readBytes())
        report("微信", result)

        assertEquals("应认成微信", "wechat", result.format?.id)
        val unusable = result.verdicts.filterIsInstance<RowVerdict.Unusable>()
        assertTrue("不该有解析不了的行，实际：${unusable.take(5).map { it.reason }}", unusable.isEmpty())
        assertTrue("应解析出消费行", result.consumptionRows.isNotEmpty())

        // 微信的第三档实测写的是 `/`（不是文档里推测的「中性交易」），
        // 对应充值/提现那几笔。它必须被排除，而不是当成方向无法识别
        assertTrue(
            "应识别出收/支为「/」的中性交易并排除",
            result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
                .any { it.reason.contains("资金转移") },
        )
    }

    /** 只打印**聚合量**：笔数与分类计数，不含任何商户名、单号、金额明细。 */
    private fun report(name: String, result: ParseResult) {
        val excluded = result.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
        println(
            "[$name] 格式=${result.format?.id} 消费=${result.consumptionRows.size} " +
                "排除=${excluded.size} 不可用=${result.verdicts.filterIsInstance<RowVerdict.Unusable>().size} " +
                "告警=${result.warnings}",
        )
        println("[$name] 排除理由分布=" + excluded.groupingBy { it.reason.take(28) }.eachCount())
    }
}
