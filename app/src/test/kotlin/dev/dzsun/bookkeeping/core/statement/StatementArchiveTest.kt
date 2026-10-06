package dev.dzsun.bookkeeping.core.statement

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夹具 `wechat-encrypted.zip` 是用 **Info-ZIP 的 `zip -e`** 造的真实加密包
 * （密码 `123456`），不是照算法手工拼的字节。
 *
 * 这一点很要紧：如果夹具也是我按同一份理解造的，那测的只是"我的理解与我的理解一致"，
 * 而真正会出错的地方恰恰是我对格式的理解。用真实工具造，才测到了那份理解本身。
 */
class StatementArchiveTest {

    private val fixture = File("src/test/resources/statements/wechat-encrypted.zip")
    private val password = "123456"

    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        Json { ignoreUnknownKeys = true }
            .decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    @Test
    fun `用的是 ZipCrypto 加密而不是 AES`() {
        // 这个断言是防止将来有人把夹具换成 AES 加密的包——
        // 那时测试会以"密码错误"的名义失败，而真正的原因是算法不支持，容易被误诊
        val info = zipInfoOf(fixture)
        assertTrue("夹具应当是加密的", info.encrypted)
        assertEquals("夹具应当是 deflate 压缩", 8, info.method)
    }

    @Test
    fun `密码正确时能取出 CSV`() {
        val result = StatementArchive.open(fixture.readBytes(), password)
        assertTrue("应当是 Extracted，实际 $result", result is ArchiveExtraction.Extracted)
        val text = (result as ArchiveExtraction.Extracted).bytes.decodeToString()
        assertTrue("内容应含表头", text.contains("交易时间"))
        assertTrue("内容应含商户", text.contains("星巴克咖啡"))
    }

    @Test
    fun `密码错误时明确返回 WrongPassword`() {
        val result = StatementArchive.open(fixture.readBytes(), "000000")
        assertTrue("应当是 WrongPassword，实际 $result", result is ArchiveExtraction.WrongPassword)
    }

    @Test
    fun `没给密码时返回 NeedsPassword 而不是报错`() {
        // 界面要据此弹出密码输入框，所以这是一个正常状态，不是失败
        val result = StatementArchive.open(fixture.readBytes(), null)
        assertTrue("应当是 NeedsPassword，实际 $result", result is ArchiveExtraction.NeedsPassword)
    }

    @Test
    fun `裸 CSV 不当作压缩包`() {
        val result = StatementArchive.open("交易时间,金额\n".toByteArray(), password)
        assertTrue(result is ArchiveExtraction.NotZip)
    }

    @Test
    fun `不是 zip 的字节不会被 isZip 误判`() {
        assertFalse(StatementArchive.isZip("PK".toByteArray()))
        assertFalse(StatementArchive.isZip("hello,world\n1,2\n".toByteArray()))
    }

    // ------------------------------------------------------------ 端到端

    @Test
    fun `加密账单端到端：解密后能正确解析出账目`() {
        // 这是这条链真正的验收：真实加密包 → 解密 → 解析 → 归一化的行
        val extracted = StatementArchive.open(fixture.readBytes(), password)
        val bytes = (extracted as ArchiveExtraction.Extracted).bytes

        val parsed = CsvStatementParser(formats).parse(bytes)
        assertEquals("wechat", parsed.format?.id)

        val rows = parsed.consumptionRows
        assertEquals("两笔消费、一笔转账被排除", 2, rows.size)

        val coffee = rows.first { it.merchant == "星巴克咖啡" }
        assertEquals(38_50L, coffee.amount.amountMinor)
        assertEquals("拿铁,大杯", coffee.description)
        assertEquals(StatementDirection.EXPENSE, coffee.direction)

        val big = rows.first { it.merchant == "永辉超市" }
        assertEquals(123_456L, big.amount.amountMinor)
    }

    @Test
    fun `端到端时转账被排除且给出理由`() {
        val extracted = StatementArchive.open(fixture.readBytes(), password) as ArchiveExtraction.Extracted
        val parsed = CsvStatementParser(formats).parse(extracted.bytes)
        val excluded = parsed.verdicts.filterIsInstance<RowVerdict.NotConsumption>()
        assertEquals(1, excluded.size)
        assertTrue(excluded.first().reason.contains("转账"))
    }

    // ------------------------------------------------------------ 工具

    private data class ZipInfo(val encrypted: Boolean, val method: Int)

    /** 极简地读一下本地文件头，只取测试要断言的两个字段。 */
    private fun zipInfoOf(file: File): ZipInfo {
        val bytes = file.readBytes()
        val flags = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
        val method = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
        return ZipInfo(encrypted = flags and 0x1 != 0, method = method)
    }
}
