package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载进度的**文案与通知核**——与 [ModelWorkControllerTest] 同一套钉法：
 * 禁止造假进度在这里的落点是「速度/剩余没有分母就不显示」。
 */
class ModelDownloadProgressTest {

    // ---------- speedLine ----------

    @Test
    fun `速度行有分母时给MB每秒`() {
        // 60 MB / 30 s = 2.0 MB/s
        assertEquals("2.0 MB/s", speedLine(60L * 1024 * 1024, 30_000L))
    }

    @Test
    fun `低速走KB每秒`() {
        // 100 KB / 10 s = 10 KB/s
        assertEquals("10 KB/s", speedLine(100L * 1024, 10_000L))
    }

    @Test
    fun `会话字节为零或负不造速度`() {
        assertNull(speedLine(0L, 10_000L))
        assertNull(speedLine(-1L, 10_000L))
    }

    @Test
    fun `没有起点时间不造速度`() {
        assertNull(speedLine(1000L, null))
     }

    @Test
    fun `用时为零不除零`() {
        assertNull(speedLine(1000L, 0L))
    }

    // ---------- etaLine ----------

    @Test
    fun `剩余时间秒级`() {
        assertEquals("约 30 秒", etaLine(3000L, 100.0))
    }

    @Test
    fun `剩余时间分钟级`() {
        // 120 秒 → 约 2 分钟（90 秒以下才是秒级——避免「约 89 秒」抖动）
        assertEquals("约 2 分钟", etaLine(120_000L, 1000.0))
    }

    @Test
    fun `剩余时间小时级`() {
        assertEquals("约 2 小时 30 分", etaLine(9_000_000L, 1000.0))
    }

    @Test
    fun `速度为零不出剩余`() {
        assertNull(etaLine(1000L, 0.0))
        assertNull(etaLine(0L, 100.0))
    }

    // ---------- downloadLine ----------

    @Test
    fun `下载行有速度有剩余有百分比`() {
        // done 512MB / total 1024MB，起点 0，已用 60s → 8.5 MB/s？512/60=8.53
        val line = downloadLine(512L * 1024 * 1024, 1024L * 1024 * 1024, 0L, 60_000L)
        assertTrue(line.contains("已下载 512 / 共 1024 MB"))
        assertTrue(line.contains("· 50%"))
        assertTrue(line.contains("MB/s"))
        assertTrue(line.contains("约"))
    }

    @Test
    fun `续传后速度按会话起点算不按总平均`() {
        // 总 1024MB，会话前已有 1023MB（起点），本会话下了 1MB 用 1 秒。
        // 若错按总平均：1024MB/1000s ≈ 1 MB/s（低估 1000 倍）。
        // 正确按会话：1MB/1s = 1.0 MB/s
        val line = downloadLine(1024L * 1024 * 1024, 1024L * 1024 * 1024, 1023L * 1024 * 1024, 1_000L)
        assertTrue("速度应按会话算：$line", line.contains("1.0 MB/s"))
    }

    @Test
    fun `没有速度时下载行只有已下载和百分比`() {
        // 会话没推进（起点==done）：不造速度、不造剩余
        val line = downloadLine(100L * 1024 * 1024, 1024L * 1024 * 1024, 100L * 1024 * 1024, 60_000L)
        assertEquals("已下载 100 / 共 1024 MB · 9%", line)
        assertFalse(line.contains("KB/s"))
        assertFalse(line.contains("MB/s"))
    }

    @Test
    fun `总长未知时无百分比也无进度条`() {
        val line = downloadLine(50L * 1024 * 1024, -1L, 0L, 60_000L)
        assertFalse(line.contains("%"))
        assertNull(releasePercent(50L * 1024 * 1024, -1L))
    }

    // ---------- progressText / resultText 集成 ----------

    @Test
    fun `下载中的通知标题是下载模型`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.DOWNLOAD,
            phase = UiPhase.DOWNLOADING,
            startedAtMs = 0L,
            bytesDone = 256L * 1024 * 1024,
            bytesTotal = 1024L * 1024 * 1024,
        )
        val pair = progressText(p, nowMs = 10_000L)!!
        assertEquals("下载模型", pair.first)
        assertTrue(pair.second.contains("已下载 256 / 共 1024 MB"))
    }

    @Test
    fun `下载收场标题区分成败`() {
        assertEquals("模型下载完成", resultText(ModelWorkResult(ModelWorkKind.DOWNLOAD, true, "ok"))!!.first)
        assertEquals("模型下载未完成", resultText(ModelWorkResult(ModelWorkKind.DOWNLOAD, false, "断网"))!!.first)
    }
}
