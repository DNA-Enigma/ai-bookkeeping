package dev.dzsun.bookkeeping.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 用 JDK 内置 [HttpServer] 起真 HTTP 服务，钉下载器的**协议行为**与**校验行为**：
 *  - `expectedSha256` 是必填的，任何成功路径都真的比对过它；
 *  - 已有文件先验后信：对得上才不发请求，对不上就删掉重下；
 *  - 有 .part 时必须带 Range 头，服务器回 206 才能续写；302 重定向后 Range 不能丢；
 *  - 服务器 200 全量重发时旧 .part 必须作废；
 *  - SHA256 覆盖**完整文件**（含续传前的字节），不是只算本次增量；
 *  - 校验不匹配报 ChecksumMismatch 且 `.part` 的处置权在调用方；
 *  - 大小不符（连接提前断）不转正，.part 保留。
 *
 * 目录用 `java.io.tmpdir` 下的手工目录，`@After` 全清。
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
        // 302 到真文件：验证「跨重定向后 Range 头还在」这条链，
        // 镜像站的直链正是 302 到签名地址的形态。
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/model.gguf")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
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

    /**
     * 默认拿**当前 payload 的真摘要**与真字节数——这样每个用例默认就在测「校验通过」，
     * 要测不匹配必须显式造一个错值，不会有「忘了给 sha 于是静默跳过」的写法。
     */
    private fun spec(
        sha: String = ModelDownloader.sha256Hex(payload),
        url: String = url(),
    ) = ModelDownloadSpec(url, "model.gguf", sha, expectedBytes = payload.size.toLong())

    private fun downloader() = ModelDownloader(dir)

    private fun partOf(s: ModelDownloadSpec) = File(dir, "${s.fileName}.part")

    private fun targetOf(s: ModelDownloadSpec) = File(dir, s.fileName)

    // ---------- 全新下载 ----------

    @Test
    fun `全新下载成功且转正`() {
        payload = "hello gguf world".toByteArray()
        val result = downloader().download(spec())

        assertTrue("应成功：$result", result is ModelDownloadResult.Success)
        assertEquals(payload.size.toLong(), targetOf(spec()).length())
        assertFalse(partOf(spec()).exists())
        assertEquals(listOf(""), requests) // 没带 Range
    }

    @Test
    fun `SHA256校验通过给真实哈希`() {
        payload = "hello gguf world".toByteArray()
        val expected = ModelDownloader.sha256Hex(payload)
        val result = downloader().download(spec(expected))
        assertTrue("应成功：$result", result is ModelDownloadResult.Success)
        assertEquals(expected, (result as ModelDownloadResult.Success).sha256)
    }

    @Test
    fun `SHA256不匹配报ChecksumMismatch且保留现场给调用方`() {
        payload = "hello gguf world".toByteArray()
        val wrong = ModelDownloader.sha256Hex("different".toByteArray())
        val result = downloader().download(spec(wrong))
        assertTrue("应报校验不匹配：$result", result is ModelDownloadResult.ChecksumMismatch)
        assertEquals(wrong, (result as ModelDownloadResult.ChecksumMismatch).expected)
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
        assertTrue("应成功：$result", result is ModelDownloadResult.Success)
        assertTrue(targetOf(spec()).readBytes().contentEquals(payload))
        // 客户端确实发了 Range，服务器 200 全量，客户端弃旧 part 重写
        assertTrue(requests[0].startsWith("bytes="))
        assertFalse(partOf(spec()).exists())
    }

    @Test
    fun `302重定向后Range头不丢且按206续传`() {
        payload = "0123456789abcdef".toByteArray()
        partOf(spec()).writeBytes(payload.copyOfRange(0, 6))

        val result = downloader().download(spec(url = "http://127.0.0.1:$port/redirect"))
        assertTrue("重定向后续传应成功（Range 必须跟到最终地址）：$result", result is ModelDownloadResult.Success)
        assertTrue(targetOf(spec()).readBytes().contentEquals(payload))
        assertEquals("bytes=6-", requests.last())
        assertEquals(payload.size.toLong(), (result as ModelDownloadResult.Success).bytes)
    }

    @Test
    fun `文件大小不符时part保留供下次续传`() {
        payload = "0123456789".toByteArray()
        truncateAt = 4
        val result = downloader().download(spec())
        assertTrue("应失败：$result", result is ModelDownloadResult.Failed)
        // 不断言具体文案：断流由 JDK 的 HttpURLConnection 先抛 IOException: Premature EOF
        // （「下载中断：…」）而不是读满短读后由「下载不完整」兜住——哪条先触发取决于
        // 连接怎么断的，两条都是合法失败。这里只钉**用户真正在意的那三件事**：
        // 报失败、已下字节保住（续传本钱）、半截文件绝不叫正式名。
        assertEquals(4L, partOf(spec()).length())
        assertFalse(targetOf(spec()).exists())
    }

    // ---------- 已有完整文件 ----------

    @Test
    fun `目标已存在且校验通过时不发请求直接成功`() {
        payload = "already here".toByteArray()
        targetOf(spec()).writeBytes(payload)
        val result = downloader().download(spec())
        assertTrue("应直接成功：$result", result is ModelDownloadResult.Success)
        assertEquals("不该为一个已校验的文件再发一次请求", 0, requests.size)
        assertEquals(payload.size.toLong(), (result as ModelDownloadResult.Success).bytes)
    }

    @Test
    fun `目标已存在但哈希不符时删掉重下`() {
        payload = "0123456789abcdef".toByteArray()
        val s = spec()
        // 大小一样、内容不同：只有真算哈希才发现得了
        targetOf(s).writeBytes(ByteArray(payload.size) { 'x'.code.toByte() })

        val result = downloader().download(s)
        assertTrue("陈旧文件应被删掉并重下：$result", result is ModelDownloadResult.Success)
        assertEquals(1, requests.size)
        assertTrue(targetOf(s).readBytes().contentEquals(payload))
    }

    @Test
    fun `大小不符时不算哈希直接判不符`() {
        payload = "0123456789abcdef".toByteArray()
        val s = spec()
        targetOf(s).writeBytes(ByteArray(5))

        val existing = downloader().inspectExisting(s)
        assertTrue("应判不符：$existing", existing is ExistingModelFile.Mismatch)
        existing as ExistingModelFile.Mismatch
        assertNull("大小先对不上就不该把整文件读一遍算哈希", existing.actualSha256)
        assertEquals(5L, existing.bytes)
    }

    @Test
    fun `空文件视同不存在`() {
        payload = "abc".toByteArray()
        val s = spec()
        targetOf(s).writeBytes(ByteArray(0))

        assertTrue("0 字节不构成任何证据", downloader().inspectExisting(s) is ExistingModelFile.Absent)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消后part保留且目标不转正`() {
        payload = "0123456789abcdef".toByteArray()
        // 第一次轮询放行，写下整块后第二次轮询返回 true → 协作退出
        var polls = 0
        val result = downloader().download(spec(), cancelled = { ++polls > 1 })

        assertTrue("应报取消：$result", result is ModelDownloadResult.Failed)
        assertEquals(ModelDownloader.CANCELLED, (result as ModelDownloadResult.Failed).message)
        assertEquals("已下的字节就是下次续传的本钱", payload.size.toLong(), partOf(spec()).length())
        assertFalse("半截文件绝不许叫正式名", targetOf(spec()).exists())
    }
}
