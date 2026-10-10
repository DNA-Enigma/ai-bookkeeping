package dev.dzsun.bookkeeping.feature.settings

import com.arm.aichat.InferenceEngine
import dev.dzsun.bookkeeping.llm.ModelIssue
import dev.dzsun.bookkeeping.llm.SessionState
import dev.dzsun.bookkeeping.llm.issueMessage
import dev.dzsun.bookkeeping.llm.sessionStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页状态必须**订阅属主**（软测 TC-03/13/18）：
 * 功能页加载了设置页要跟着显示「已加载」，面板自动重载不许退回「未加载」，
 * 设置页卸载后属主回到 Idle（对话页那边由 ensureReady 给出原因，已另有覆盖）。
 */
class OnDeviceStateSyncTest {

    private val path = "/data/models/spark.gguf"

    // ---------- 属主状态推导：sessionStateOf ----------

    @Test
    fun `模型就绪且提示词已加载 → Ready`() {
        assertEquals(SessionState.Ready, sessionStateOf(InferenceEngine.State.ModelReady, loaded = true))
    }

    @Test
    fun `引擎在生成 → Generating`() {
        assertEquals(SessionState.Generating, sessionStateOf(InferenceEngine.State.Generating, loaded = true))
        assertEquals(SessionState.Generating, sessionStateOf(InferenceEngine.State.ProcessingUserPrompt, loaded = true))
    }

    @Test
    fun `解码系统提示中 → Loading`() {
        assertEquals(SessionState.Loading, sessionStateOf(InferenceEngine.State.ProcessingSystemPrompt, loaded = false))
        assertEquals(SessionState.Loading, sessionStateOf(InferenceEngine.State.LoadingModel, loaded = false))
    }

    @Test
    fun `引擎报错 → Error 带原因`() {
        val s = sessionStateOf(InferenceEngine.State.Error(IllegalStateException("boom")), loaded = false)
        assertTrue("Error 要带得出原因：$s", s is SessionState.Error && s.message.contains("boom"))
    }

    @Test
    fun `没加载也没在动 → Idle`() {
        assertEquals(SessionState.Idle, sessionStateOf(InferenceEngine.State.Uninitialized, loaded = false))
        assertEquals(SessionState.Idle, sessionStateOf(InferenceEngine.State.Initialized, loaded = false))
    }

    // ---------- 合成：resolveOnDeviceState 的三条判定标准 ----------

    @Test
    fun `①功能页加载后 设置页显示已加载`() {
        // 功能页加载完：属主 Ready，设置页本地没有进行中的动作
        val s = resolveOnDeviceState(SessionState.Ready, action = null, path = path)
        assertEquals(OnDeviceUiState.Ready(path), s)
    }

    @Test
    fun `②面板自动重载后 设置页不退回未加载`() {
        // 设置页进来时是 Idle（本地动作为空），面板把模型重载好之后属主变 Ready
        val before = resolveOnDeviceState(SessionState.Idle, action = null, path = path)
        assertEquals(OnDeviceUiState.Idle, before)

        val after = resolveOnDeviceState(SessionState.Ready, action = null, path = path)
        assertTrue("属主 Ready 时绝不能显示未加载，实际：$after", after is OnDeviceUiState.Ready)

        // 旧代码里本地动作停在 Idle 也一样——Idle 不是粘性动作
        val staleIdle = resolveOnDeviceState(SessionState.Ready, action = OnDeviceUiState.Idle, path = path)
        assertTrue("旧的 Idle 动作不许压过属主 Ready，实际：$staleIdle", staleIdle is OnDeviceUiState.Ready)
    }

    @Test
    fun `③设置页卸载后 属主回到 Idle 设置页显示未加载`() {
        // 卸载 = session.unload()（loadedKey 清空）+ 本地动作清空
        val s = resolveOnDeviceState(SessionState.Idle, action = null, path = path)
        assertEquals(OnDeviceUiState.Idle, s)
        // 对话页那半边：重新 ensureReady 时给得出「为什么不行」的原因
        assertTrue(issueMessage(ModelIssue.NoModelFile).contains("加载模型"))
    }

    // ---------- 合成的其余口径 ----------

    @Test
    fun `用户动作进行中优先于属主状态`() {
        val installing = OnDeviceUiState.Installing(0.5f, 1L, 2L)
        assertEquals(installing, resolveOnDeviceState(SessionState.Ready, installing, path))
        assertEquals(OnDeviceUiState.Loading(path), resolveOnDeviceState(SessionState.Ready, OnDeviceUiState.Loading(path), path))
        assertEquals(
            OnDeviceUiState.Running(path, "半截输出"),
            resolveOnDeviceState(SessionState.Generating, OnDeviceUiState.Running(path, "半截输出"), path),
        )
    }

    @Test
    fun `设置页自己生成的耗时结算在属主 Ready 下保留`() {
        val done = OnDeviceUiState.Done(path, "答", 800L, 3000L, 12)
        assertEquals(done, resolveOnDeviceState(SessionState.Ready, done, path))
    }

    @Test
    fun `加载失败的原因不被属主 Idle 冲掉`() {
        val failed = OnDeviceUiState.Failed("模型加载失败：xx")
        assertEquals(failed, resolveOnDeviceState(SessionState.Idle, failed, path))
    }

    @Test
    fun `属主报错时没有更具体的本地原因就展示属主的`() {
        val s = resolveOnDeviceState(SessionState.Error("引擎炸了"), action = null, path = path)
        assertEquals(OnDeviceUiState.Failed("引擎炸了"), s)
    }
}
