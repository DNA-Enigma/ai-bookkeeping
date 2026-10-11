package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等待可视化的三条口径各自钉住：
 * ① 阶段只能从真实引擎状态导出；② 没有分母不许给百分比；③ 文案永远是「阶段 + 已用时 + 预期」，
 * 不允许退化成一句没有阶段、没有计时的「正在加载…」。
 */
class ModelProgressTest {

    // ---------- ① 阶段推导 ----------

    @Test
    fun `加载权重与解码系统提示必须是两个阶段`() {
        assertEquals(UiPhase.LOADING_WEIGHTS, phaseOf(InferenceEngine.State.LoadingModel))
        assertEquals(UiPhase.DECODING_PROMPT, phaseOf(InferenceEngine.State.ProcessingSystemPrompt))
        // 合成一个「加载中」等于让用户盯 4 分钟同一句话，看不出卡在哪
        assertFalse(phaseOf(InferenceEngine.State.LoadingModel) == phaseOf(InferenceEngine.State.ProcessingSystemPrompt))
    }

    @Test
    fun `生成类状态都折成 GENERATING`() {
        assertEquals(UiPhase.GENERATING, phaseOf(InferenceEngine.State.Generating))
        assertEquals(UiPhase.GENERATING, phaseOf(InferenceEngine.State.ProcessingUserPrompt))
    }

    @Test
    fun `非等待态一律 IDLE 界面自己显示未加载或失败`() {
        assertEquals(UiPhase.IDLE, phaseOf(InferenceEngine.State.Uninitialized))
        assertEquals(UiPhase.IDLE, phaseOf(InferenceEngine.State.Initialized))
        assertEquals(UiPhase.IDLE, phaseOf(InferenceEngine.State.ModelReady))
        assertEquals(UiPhase.IDLE, phaseOf(InferenceEngine.State.Error(RuntimeException("boom"))))
    }

    @Test
    fun `每种等待阶段都有阶段名`() {
        UiPhase.entries.forEach { phase ->
            assertTrue("阶段名不能为空：$phase", phaseLabel(phase).isNotBlank())
        }
        assertEquals("解码系统提示", phaseLabel(UiPhase.DECODING_PROMPT))
        assertEquals("释放内置模型", phaseLabel(UiPhase.RELEASING))
    }

    @Test
    fun `进行中的阶段都带预期时长提示`() {
        // IDLE 不是等待阶段，允许没有提示；其余必须给出「还要多久」的预期
        UiPhase.entries.filter { it != UiPhase.IDLE }.forEach { phase ->
            assertTrue("$phase 缺预期提示", phaseHint(phase).isNotBlank())
        }
    }

    @Test
    fun `首次加载类阶段要提示可以先去干别的`() {
        listOf(UiPhase.RELEASING, UiPhase.LOADING_WEIGHTS, UiPhase.DECODING_PROMPT)
            .forEach { assertTrue(it.isFirstLoadWait) }
        assertFalse(UiPhase.GENERATING.isFirstLoadWait)
        assertFalse(UiPhase.DISPATCH.isFirstLoadWait)
        assertTrue(FIRST_WAIT_HINT.contains("完成后本页自动更新"))
    }

    // ---------- ② 已用时 ----------

    @Test
    fun `已用时按秒进位不给小数`() {
        assertEquals("0 秒", formatElapsed(0L))
        assertEquals("12 秒", formatElapsed(12_000L))
        assertEquals("59 秒", formatElapsed(59_999L))
        assertEquals("1 分 5 秒", formatElapsed(65_000L))
        assertEquals("4 分 27 秒", formatElapsed(267_000L))
        assertEquals("1 小时 2 分", formatElapsed(3_725_000L))
    }

    @Test
    fun `负数时长不许显示负秒`() {
        assertEquals("0 秒", formatElapsed(-500L))
    }

    @Test
    fun `主行是阶段加已用时`() {
        assertEquals("解码系统提示 · 已用 1 分 20 秒", waitHeadline(UiPhase.DECODING_PROMPT, 80_000L))
        // 没有起点就只给阶段名，不编一个「已用 0 秒」出来
        assertEquals("解码系统提示", waitHeadline(UiPhase.DECODING_PROMPT, null))
    }

    // ---------- ③ 只有释放有分母 ----------

    @Test
    fun `没有分母就不给百分比`() {
        assertNull(releasePercent(bytesDone = 100L, bytesTotal = 0L))
        assertNull(releasePercent(bytesDone = 100L, bytesTotal = -1L))
        assertNull(releasePercent(bytesDone = -1L, bytesTotal = 1056L))
    }

    @Test
    fun `释放进度按字节算且不越界`() {
        assertEquals(0, releasePercent(0L, 1056L))
        assertEquals(48, releasePercent(512L, 1056L))
        assertEquals(100, releasePercent(1056L, 1056L))
        // 上报节流可能略微超过分母（coerceAtMost 已在安装器里做，这里再钉一次）
        assertEquals(100, releasePercent(2000L, 1056L))
    }

    @Test
    fun `释放行带已释放与共多少兆`() {
        val line = releaseLine(512L * 1024 * 1024, 1056L * 1024 * 1024, 12_000L)
        assertEquals("已释放 512 / 共 1056 MB · 48% · 已用 12 秒", line)
    }

    // ---------- 失败文案：原因 + 动作 ----------

    @Test
    fun `失败文案是具体原因加建议动作`() {
        val lines = failureText("模型加载失败：磁盘满", "点「卸载」后重新加载")
        assertEquals(2, lines.size)
        assertEquals("模型加载失败：磁盘满", lines[0])
        assertEquals("点「卸载」后重新加载", lines[1])
    }

    @Test
    fun `原因本身已含下一步时不硬塞第二句`() {
        val lines = failureText("未找到模型文件，去设置页点「加载模型」")
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("设置页"))
    }

    @Test
    fun `空白原因不产生空行`() {
        assertEquals(listOf("引擎炸了"), failureText("引擎炸了", "  "))
        assertEquals(emptyList<String>(), failureText("   "))
    }
}
