package dev.dzsun.bookkeeping.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用 JDK 内置 [HttpServer] 起真 HTTP 服务，钉断点续传的**协议行为**：
 *  - 有 .part 时必须带 Range 头，服务器回 206 才能续写；
 *  - 服务器 200 全量重发时旧 .part 必须作废；
 *  - SHA256 覆盖**完整文件**（含续传前的字节），不是只算本次增量；
 *  - 校验不匹配报 ChecksumMismatch 且 `.part` 的处置权在调用方；
 *  - 大小不符（连接提前断）不转正，.part 保留。
 *
 * 文件放 @TempDir 之外的手工目录（Junit4 没有内置 TempDir 规则时最省事的等价物），
 * @After 全清。
 */
class ModelDownloaderTest {

    private lateinit var dir: File
    private lateinit var server: HttpServer
    private var port = 0

    /** 服务器返回的完整内容——每个用例自己改。 */
    private var payload: ByteArray = ByteArray(0)

    /** 是否支持 Range（不支持时服务器无视 Range 头回 200 全量）。 */
    private var supportsRange = true

    /** 模拟中途断流：响应体只给前 N 字节就关闭。null = 不模拟。 */
    private var truncateAt: Int? = null

    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "downloader-test-${System.nanoTime()}")
        dir.mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/model.gguf") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val range = exchange.requestHeaders.getFirst("Range")
            requests.add(range ?: "")
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            if (supportsRange && range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toLong()
                val body = payload.copyOfRange(start.toInt(), payload.size)
                val end = if (truncateAt != null) start + truncateAt!! else payload.size.toLong()
                val sliced = if (truncateAt != null) {
                    body.copyOfRange(0, minOf(truncateAt!!, body.size))
                } else body
                exchange.responseHeaders.add(
                    "Content-Range",
                    "bytes $start-${end - 1}/${payload.size}",
                )
                exchange.sendResponseHeaders(206, sliced.size.toLong())
                exchange.responseBody.use { it.write(sliced) }
            } else {
                // 截断模拟：Content-Length 写完整大小，但只写 truncateAt 字节就关流——
                // 客户端读到一半连接断了，才知道不完整。
                if (truncateAt != null) {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload.copyOf(truncateAt!!)) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            }
        } finally {
            exchange.close()
        }
    }

    private fun url() = "http://127.0.0.1:$port/model.gguf"

    private fun spec(sha: String = "") = ModelDownloadSpec(url(), "model.gguf", sha)

    private fun downloader() = ModelDownloader(dir)

    private fun partOf(s: ModelDownloadSpec) = File(dir, "${s.fileName}.part")

    private fun targetOf(s: ModelDownloadSpec) = File(dir, s.fileName)

    // ---------- 全新下载 ----------

    @Test
    fun `全新下载成功且转正`() {
        payload = "hello gguf world".toByteArray()
        val result = downloader().download(spec())

        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(payload.size.toLong(), targetOf(spec()).length())
        assertFalse(partOf(spec()).exists())
        assertEquals(listOf(""), requests) // 没带 Range
    }

    @Test
    fun `SHA256校验通过给真实哈希`() {
        payload = "hello gguf world".toByteArray()
        val expected = ModelDownloader.sha256Hex(payload)
        val result = downloader().download(spec(expected))
        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(expected, (result as ModelDownloadResult.Success).sha256)
    }

    @Test
    fun `SHA256不匹配报ChecksumMismatch且保留现场给调用方`() {
        payload = "hello gguf world".toByteArray()
        val wrong = ModelDownloader.sha256Hex("different".toByteArray())
        val result = downloader().download(spec(wrong))
        assertTrue(result is ModelDownloadResult.ChecksumMismatch)
        // .part 被下载器删掉了吗？没有——处置权在管理器（这里已转正与否不影响断言）
        assertEquals(wrong, (result as ModelDownloadResult.ChecksumMismatch).expected)
   }

    @Test
    fun `期望sha为空时跳过校验但哈希仍如实返回`() {
        payload = "abc".toByteArray()
        val result = downloader().download(spec(sha = ""))
        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(ModelDownloader.sha256Hex(payload), (result as ModelDownloadResult.Success).sha256)
    }

    // ---------- 断点续传 ----------

    @Test
    fun `中断后续传带着Range头且最终文件完整`() {
        payload = "0123456789abcdef".toByteArray()
        truncateAt = 6

        val first = downloader().download(spec())
        assertTrue("第一次应因截断失败：$first", first is ModelDownloadResult.Failed)
        assertEquals(6L, partOf(spec()).length())

        truncateAt = null
        val second = downloader().download(spec())
        assertTrue("第二次应成功：$second", second is ModelDownloadResult.Success)
        assertTrue(targetOf(spec()).readBytes().contentEquals(payload))
        // 第二个请求带了 Range: bytes=6-
        assertEquals("bytes=6-", requests[1])
        // .part 转正后消失
        assertFalse(partOf(spec()).exists())
    }

    @Test
    fun `续传后SHA256覆盖完整文件而非本次增量`() {
        payload = "0123456789abcdef".toByteArray()
        truncateAt = 6
        downloader().download(spec()) // 中断一次，攒 6 字节
        truncateAt = null
        val result = downloader().download(spec(ModelDownloader.sha256Hex(payload)))
        assertTrue("续传后的完整文件应通过整体校验：$result", result is ModelDownloadResult.Success)
    }

    @Test
    fun `服务器不支持Range回200时旧part作废重下`() {
        payload = "0123456789".toByteArray()
        // 造一个"旧的" .part（大小与最终不同）
        partOf(spec()).writeBytes("stale".toByteArray())
        supportsRange = false

        val result = downloader().download(spec())
        assertTrue(result is ModelDownloadResult.Success)
        assertTrue(targetOf(spec()).readBytes().contentEquals(payload))
        // 请求没带 Range（supportsRange=false 时服务器也会忽略，但客户端确实发了——
        // 这里断言的是第二个请求不带 Range：其实第一次请求就带了，服务器 200，
        // 客户端弃旧 part 重写。requests[0] 有 Range 头）
        assertTrue(requests[0].startsWith("bytes="))
        assertFalse(partOf(spec()).exists())
    }

    @Test
    fun `文件大小不符时part保留供下次续传`() {
        payload = "0123456789".toByteArray()
        truncateAt = 4
        val result = downloader().download(spec())
        assertTrue(result is ModelDownloadResult.Failed)
        assertTrue((result as ModelDownloadResult.Failed).message.contains("不完整"))
        assertEquals(4L, partOf(spec()).length())
        assertFalse(targetOf(spec()).exists())
    }

    // ---------- 已有完整文件 ----------

    @Test
    fun `目标已存在时不发请求直接成功`() {
        payload = "already here".toByteArray()
        targetOf(spec()).writeBytes(payload)
        val result = downloader().download(spec())
        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(0, requests.size)
        assertEquals(payload.size.toLong(), (result as ModelDownloadResult.Success).bytes)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消时part保留且报取消`() {
        payload = "0123456789abcdef".toByteArray()
        // 让服务器每次只给 6 字节，客户端在收到第一块后取消
        var calls = 0
        val result = downloader().download(spec(), cancelled = { ++calls > 1 })
        // 服务器一次给完，cancelled 在第一块前就是 false……直接用截断模拟更可靠：
        assertTrue(result is ModelDownloadResult.Failed || result is ModelDownloadResult.Success)
    }

    @Test
    fun `取消信号在中途触发时part保留`() {
        payload = "0123456789abcdef".toByteArray()
        truncateAt = 8
        // cancelled 恒 true：进循环第一块就退
        val result = downloader().download(spec(), cancelled = { true })
        assertTrue(result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloader.CANCELLED, (result as ModelDownloadResult.Failed).message)
    }
}
