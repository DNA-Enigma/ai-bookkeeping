package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知文案与判定的**纯函数核**——服务/控制器只是渲染器，文案在这里逐字钉。
 *
 * 禁止造假进度那条口径在这里的落点：
 *  - 没有分母（bytesTotal <= 0）时 [progressText] 走「阶段+已用时」，绝不出现百分比；
 *  - [ModelWorkProgress.startedAtMs] 是起点不是时长，[progressText] 负责相减——
 *    直接把起点当时长喂给 [releaseLine] 会显示「已用 83 小时」，这里钉一个用例防回归。
 */
class ModelWorkControllerTest {

    // ---------- progressText：进行中 ----------

    @Test
    fun `生成中的通知标题是本机模型思考中`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.GENERATE,
            phase = UiPhase.GENERATING,
            startedAtMs = 1000L,
        )
        val pair = progressText(p, nowMs = 7000L)!!
        assertEquals("本机模型思考中", pair.first)
        // 1000 → 7000 是 6 秒：起点与当前时刻相减，不是把起点当时长
        assertEquals("生成回答 · 已用 6 秒", pair.second)
    }

    @Test
    fun `加载中走 init 标题且正文带阶段名`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.INIT,
            phase = UiPhase.DECODING_PROMPT,
            startedAtMs = 0L,
        )
        val pair = progressText(p, nowMs = 61_000L)!!
        assertEquals("本机模型准备中", pair.first)
        assertEquals("解码系统提示 · 已用 1 分 1 秒", pair.second)
    }

    @Test
    fun `释放中正文是MB行有真分母`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.RELEASE,
            phase = UiPhase.RELEASING,
            startedAtMs = null,
            bytesDone = 512L * 1024 * 1024,
            bytesTotal = 1056L * 1024 * 1024,
        )
        val pair = progressText(p, nowMs = null)!!
        assertEquals("释放内置模型", pair.first)
        // releaseLine 口径：字节转 MB + 百分比；没有起点就没有「已用」尾巴
        assertEquals("已释放 512 / 共 1056 MB · 48%", pair.second)
    }

    @Test
    fun `释放中带起点时正文带已用时`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.RELEASE,
            phase = UiPhase.RELEASING,
            startedAtMs = 0L,
            bytesDone = 512L * 1024 * 1024,
            bytesTotal = 1056L * 1024 * 1024,
        )
        val pair = progressText(p, nowMs = 26_000L)!!
        assertEquals("已释放 512 / 共 1056 MB · 48% · 已用 26 秒", pair.second)
    }

    @Test
    fun `时钟回拨时已用时钳到零不出负数`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.GENERATE,
            phase = UiPhase.GENERATING,
            startedAtMs = 7000L,
        )
        val pair = progressText(p, nowMs = 1000L)!!
        assertEquals("生成回答 · 已用 0 秒", pair.second)
    }

    @Test
    fun `释放中分母缺失时不出现百分比`() {
        val p = ModelWorkProgress(
            kind = ModelWorkKind.RELEASE,
            phase = UiPhase.RELEASING,
            bytesDone = -1L,
            bytesTotal = -1L,
        )
        val pair = progressText(p, nowMs = null)!!
        assertFalse(pair.second.contains("%"))
        assertFalse(pair.second.contains("MB"))
    }

    @Test
    fun `无等待且无结果返回null`() {
        assertNull(progressText(ModelWorkProgress(), nowMs = 0L))
    }

    @Test
    fun `阶段IDLE即使kind残留也不播进行中`() {
        val p = ModelWorkProgress(kind = ModelWorkKind.GENERATE, phase = UiPhase.IDLE)
        assertNull(progressText(p, nowMs = 0L))
    }

    // ---------- resultText：收场 ----------

    @Test
    fun `生成成功通知标题为回答已生成`() {
        val r = ModelWorkResult(ModelWorkKind.GENERATE, ok = true, message = "¥28 打车，可归为交通支出。")
        val pair = resultText(r)!!
        assertEquals("回答已生成", pair.first)
        assertEquals("¥28 打车，可归为交通支出。", pair.second)
    }

    @Test
    fun `生成失败标题为生成没有完成且原因原样`() {
        val r = ModelWorkResult(ModelWorkKind.GENERATE, ok = false, message = "生成超时（超过 90 秒没出结果）")
        val pair = resultText(r)!!
        assertEquals("生成没有完成", pair.first)
        assertTrue(pair.second.contains("超时"))
    }

    @Test
    fun `init收场按成败给不同标题`() {
        assertEquals(
            "模型已就绪",
            resultText(ModelWorkResult(ModelWorkKind.INIT, true, "模型已就绪"))!!.first,
        )
        assertEquals(
            "模型加载失败",
            resultText(ModelWorkResult(ModelWorkKind.INIT, false, "空间不足"))!!.first,
        )
    }

    @Test
    fun `释放收场标题区分成败`() {
        assertEquals(
            "模型资产释放完成",
            resultText(ModelWorkResult(ModelWorkKind.RELEASE, true, "ok"))!!.first,
        )
        assertEquals(
            "模型资产释放失败",
            resultText(ModelWorkResult(ModelWorkKind.RELEASE, false, "写模型失败"))!!.first,
        )
    }

    @Test
    fun `结果正文超长被截到120字`() {
        val long = "x".repeat(300)
        val pair = resultText(ModelWorkResult(ModelWorkKind.GENERATE, true, long))!!
        assertEquals(120, pair.second.length)
    }

    @Test
    fun `NONE种类不是一次收场`() {
        assertNull(resultText(ModelWorkResult(ModelWorkKind.NONE, true, "x")))
    }
}
