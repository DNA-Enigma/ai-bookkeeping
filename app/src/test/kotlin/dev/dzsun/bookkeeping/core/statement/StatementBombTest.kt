package dev.dzsun.bookkeeping.core.statement

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩炸弹防护的回归。
 *
 * 每个恶意夹具都配了 `timeout`：修复前它们会走**分配 2GB 缓冲**或
 * **解压二十兆字节**那条路（OOM / 卡顿），修复后必须在毫秒级拒绝并给出理由。
 * 超时断言是「快速拒绝」这件事本身的一部分——不只是结果对，还得快。
 *
 * 反例侧（正常账单仍能导入）也在这里：只挡炸弹、不误伤正常文件的证明，
 * 和炸弹被挡住同等重要。
 */
class StatementBombTest {

    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        Json { ignoreUnknownKeys = true }.decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    // ------------------------------------------------------------ 恶意：声明体积

    @Test(timeout = 10_000)
    fun `头部声称解压出 2GB 的条目被快速拒绝而不是 OOM`() {
        // 旧实现会照这个数字 `ByteArrayOutputStream(0x7FFFFFF0)` → 直接 OOM
        val zip = rawLocalHeaderZip(
            name = "bomb.csv",
            method = METHOD_DEFLATE,
            compressed = deflate("hello,world\n".toByteArray()),
            declaredUncompressed = 0x7FFF_FFF0,
        )

        val result = StatementArchive.open(zip, "123456")
        val reason = failedReason(result)

        assertTrue("应被拒绝，实际 $result", reason.isNotEmpty())
        assertTrue("理由应说清是解压体积越界：$reason", reason.contains("解压后"))
    }

    @Test(timeout = 10_000)
    fun `高压缩比的条目按比例被拒绝`() {
        // 8 MiB 全 0，deflate 后只有几 KB：比例远超上限，正常账单够不着
        val payload = ByteArray(8 * 1024 * 1024)
        val zip = rawLocalHeaderZip("bomb.csv", METHOD_DEFLATE, deflate(payload), payload.size)

        val result = StatementArchive.open(zip, "pw")
        val reason = failedReason(result)

        assertTrue("应被拒绝，实际 $result", reason.isNotEmpty())
        assertTrue("理由应点出压缩比：$reason", reason.contains("压缩比"))
    }

    @Test(timeout = 20_000)
    fun `头部谎报体积时按实际解压量兜底拒绝`() {
        // 头部谎称只解压出 100 字节，绕开"声明体积"那道闸——
        // 真正的兜底必须是循环里按**实际产出**累计封顶，而不是信头部
        val payload = ByteArray(20 * 1024 * 1024)
        val zip = rawLocalHeaderZip("bomb.csv", METHOD_DEFLATE, deflate(payload), declaredUncompressed = 100)

        val result = StatementArchive.open(zip, "pw")
        val reason = failedReason(result)

        assertTrue("应被拒绝，实际 $result", reason.isNotEmpty())
        assertTrue("应识别为压缩炸弹：$reason", reason.contains("压缩炸弹"))
    }

    @Test(timeout = 10_000)
    fun `条目数超过上限被拒绝`() {
        // 全是非 csv 条目：旧实现会一个个跳过、走到底才说"没有 CSV"；
        // 新实现在第 65 个条目上就拒绝
        val zip = rawZip((0 until StatementLimits.MAX_ENTRIES + 5).map { "f$it.txt" to "x" })

        val result = StatementArchive.open(zip, null)
        val reason = failedReason(result)

        assertTrue("应被拒绝，实际 $result", reason.isNotEmpty())
        assertTrue("理由应点出条目数：$reason", reason.contains("条目数"))
    }

    @Test(timeout = 15_000)
    fun `超过大小上限的文件在解析前就被拒绝`() {
        val big = ByteArray(StatementLimits.MAX_ARCHIVE_BYTES + 1)

        val attempt = classifyStatement(formats, big, null)

        assertTrue("应被拒绝，实际 $attempt", attempt is ParseAttempt.Refused)
        assertTrue(
            "理由应说清是文件过大：${unreadableReason(attempt)}",
            unreadableReason(attempt).contains("过大"),
        )
    }

    // ------------------------------------------------------------ 恶意：xlsx 行列号

    @Test(timeout = 10_000)
    fun `xlsx 行号越界被拒绝而不是分配二十亿行`() {
        val xlsx = xlsxWithSheet(
            """<row r="2000000000"><c r="A1" t="inlineStr"><is><t>x</t></is></c></row>""",
        )

        val attempt = classifyStatement(formats, xlsx, null)

        assertTrue("应被拒绝，实际 $attempt", attempt is ParseAttempt.Refused)
        assertTrue("理由应点出是行号：${unreadableReason(attempt)}", unreadableReason(attempt).contains("行号"))
    }

    @Test(timeout = 10_000)
    fun `xlsx 超长列名被拒绝而不是撑出巨大列数组`() {
        // `ZZZZZZZZZ1` 的列号按 26 进制会乘到超过 Int，旧实现溢出成负数后被静默当成"没这列"
        val xlsx = xlsxWithSheet(
            """<row r="1"><c r="ZZZZZZZZZ1" t="inlineStr"><is><t>x</t></is></c></row>""",
        )

        val attempt = classifyStatement(formats, xlsx, null)

        assertTrue("应被拒绝，实际 $attempt", attempt is ParseAttempt.Refused)
        assertTrue("理由应点出是列号：${unreadableReason(attempt)}", unreadableReason(attempt).contains("列号"))
    }

    // ------------------------------------------------------------ 反例：正常账单

    @Test
    fun `正常加密账单不受新上限影响`() {
        val bytes = File("src/test/resources/statements/wechat-encrypted.zip").readBytes()

        val attempt = classifyStatement(formats, bytes, "123456")

        assertTrue("正常账单应能解析，实际 $attempt", attempt is ParseAttempt.Parsed)
        assertEquals("wechat", (attempt as ParseAttempt.Parsed).result.format?.id)
    }

    @Test
    fun `正常 xlsx 账单不受新上限影响`() {
        val attempt = classifyStatement(formats, normalWechatXlsx(), null)

        assertTrue("正常 xlsx 应能解析，实际 $attempt", attempt is ParseAttempt.Parsed)
        val parsed = (attempt as ParseAttempt.Parsed).result
        assertEquals("wechat", parsed.format?.id)
        // 不只是"认得格式"，那一行也得真解析出来——别把正常文件挡在门外
        assertEquals(1, parsed.consumptionRows.size)
        assertEquals(38_50L, parsed.consumptionRows.first().amount.amountMinor)
    }

    @Test
    fun `正常未加密的 zip+csv 仍能解压`() {
        val csv = "交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n" +
            "2026-07-06 11:48:25,餐饮美食,早餐铺,豆浆油条,支出,6.00,花呗,交易成功,2026010100000001,-,\n"
        val zip = rawZip(listOf("账单.csv" to csv))

        val result = StatementArchive.open(zip, null)
        val extracted = result as? ArchiveExtraction.Extracted

        assertTrue("正常 zip+csv 应能解压，实际 $result", extracted != null)
        assertTrue("应取到 CSV 内容", extracted!!.bytes.decodeToString().contains("豆浆油条"))
    }

    // ------------------------------------------------------------ 夹具

    private fun unreadableReason(attempt: ParseAttempt): String =
        (attempt as? ParseAttempt.Refused)?.preparation.let { it as? ImportPreparation.Unreadable }?.reason.orEmpty()

    private fun failedReason(result: ArchiveExtraction): String =
        (result as? ArchiveExtraction.Failed)?.reason.orEmpty()

    /**
     * 手工拼一个**只有本地文件头**的 zip 条目，好让头里的解压体积可以撒谎。
     * `java.util.zip` 拒绝写出不实的大小，所以这里只能自己写字节。
     */
    private fun rawLocalHeaderZip(
        name: String,
        method: Int,
        compressed: ByteArray,
        declaredUncompressed: Int,
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeLocalHeader(out, nameBytes, method, compressed.size, declaredUncompressed)
        out.write(nameBytes)
        out.write(compressed)
        return out.toByteArray()
    }

    /**
     * 一个**本地头里带真实大小**的多条目 zip（无数据描述符）。
     *
     * 不能直接用 [java.util.zip.ZipOutputStream]：它把大小写在数据之后的数据描述符里，
     * 本地头里是 0——而真实账单（Info-ZIP 打包）本地头里是有大小的，
     * [StatementArchive] 正是按本地头顺序扫的。夹具要贴合真实形状才测得到那段逻辑。
     */
    private fun rawZip(entries: List<Pair<String, String>>, compress: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val data = content.toByteArray(Charsets.UTF_8)
            val payload = if (compress) deflate(data) else data
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            writeLocalHeader(out, nameBytes, if (compress) METHOD_DEFLATE else METHOD_STORED, payload.size, data.size)
            out.write(nameBytes)
            out.write(payload)
        }
        return out.toByteArray()
    }

    /** 写一个本地文件头（30 字节）+ 名称，大小如实填写。 */
    private fun writeLocalHeader(
        out: ByteArrayOutputStream,
        nameBytes: ByteArray,
        method: Int,
        compressedSize: Int,
        uncompressedSize: Int,
    ) {
        fun le16(v: Int) {
            out.write(v and 0xff)
            out.write((v ushr 8) and 0xff)
        }
        fun le32(v: Int) {
            le16(v and 0xffff)
            le16((v ushr 16) and 0xffff)
        }
        le32(LOCAL_HEADER_SIGNATURE)
        le16(20)                 // version needed
        le16(0)                  // flags
        le16(method)             // compression method
        le16(0)                  // mod time
        le16(0)                  // mod date
        le32(0)                  // crc32（扫描时不校验）
        le32(compressedSize)     // compressed size
        le32(uncompressedSize)   // uncompressed size
        le16(nameBytes.size)     // name length
        le16(0)                  // extra length
    }

    /** 原始 deflate（nowrap），与 `Inflater(true)` 对应。 */
    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** 一个只有第一个工作表、且内容由调用方给定的最小 xlsx。 */
    private fun xlsxWithSheet(sheetRows: String): ByteArray = zipOf(
        listOf(
            "xl/workbook.xml" to """<?xml version="1.0"?><workbook/>""",
            "xl/worksheets/sheet1.xml" to
                """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                "<sheetData>$sheetRows</sheetData></worksheet>",
        ),
    )

    /** 真实形状的正常微信 xlsx：表头 + 一笔支出。用内联字符串，省去共享串表。 */
    private fun normalWechatXlsx(): ByteArray {
        val header = listOf(
            "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "当前状态", "交易单号",
        )
        fun cell(ref: String, value: String) = """<c r="$ref" t="inlineStr"><is><t>$value</t></is></c>"""
        val headerRow = header.mapIndexed { i, v -> cell(colName(i) + "1", v) }.joinToString("")
        val dataRow = listOf(
            "2026-10-06 12:00:00", "商户消费", "咖啡店", "拿铁", "支出", "38.5", "支付成功", "WX0000000000000001",
        ).mapIndexed { i, v -> cell(colName(i) + "2", v) }.joinToString("")

        return xlsxWithSheet("""<row r="1">$headerRow</row><row r="2">$dataRow</row>""")
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

    private fun zipOf(entries: List<Pair<String, String>>): ByteArray {
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

    private companion object {
        const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8
    }
}
