package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 属主自愈监视器的判定：调用方协程被取消后，属主替它认领在途系统提示。
 *
 * 背景（2026-10-10 模拟器实测）：对话页 `ensureReady` 加载中用户按返回，
 * 承载它的协程被取消，而 JNI 不受影响照常跑完 —— `_loadedKey` 的赋值语句随协程消失，
 * 引擎 ModelReady、key 却恒为 null，于是 `sessionStateOf(ModelReady, false)` 长期是 Loading，
 * 设置页永远显示「正在加载模型」，实际模型此刻完全可用。
 *
 * 安全底线是 [shouldClaimInFlight] 的 `promptDispatched` 参数：
 * ModelReady 有两种来路（loadModel 刚完 / 提示词处理完），**只有看过
 * `ProcessingSystemPrompt` 之后**的那个才许认领，防止把「提示词还没挂上」谎报成就绪。
 */
class SparkSessionClaimTest {

    /**
     * 按监视协程的判定顺序走状态序列：置位条件与认领判定全部委托给纯函数，
     * 这里只保留接线（等价于监视协程 collect 里的副作用）。
     */
    private class MonitorSim(
        val hasInFlight: Boolean = true,
        initialKey: String? = null,
    ) {
        var promptDispatched = false
        var key: String? = initialKey
        var claims = 0

        fun step(s: InferenceEngine.State) {
            if (isPromptDispatching(s)) promptDispatched = true
            if (shouldClaimInFlight(s, promptDispatched, hasInFlight, hasKey = key != null)) {
                key = "claimed"
                promptDispatched = false
                claims++
            }
        }

        fun run(states: List<InferenceEngine.State>) = states.forEach(::step)
    }

    // ---------- 场景一：导航取消（本次修复的对象） ----------

    @Test
    fun `看过 ProcessingSystemPrompt 的 ModelReady——key 空、有在途 → 认领`() {
        val sim = MonitorSim()
        sim.run(
            listOf(
                InferenceEngine.State.Initialized,
                InferenceEngine.State.LoadingModel,
                InferenceEngine.State.ProcessingSystemPrompt,
                InferenceEngine.State.ModelReady,
            ),
        )
        assertEquals("取消场景要被自愈认领", "claimed", sim.key)
        assertEquals(1, sim.claims)
    }

    @Test
    fun `没看过 ProcessingSystemPrompt 的 ModelReady——不认领（安全底线）`() {
        // loadModel 刚完成、setSystemPrompt 还没开始的 ModelReady：提示词没挂上，认领=谎报就绪
        val sim = MonitorSim()
        sim.run(
            listOf(
                InferenceEngine.State.Initialized,
                InferenceEngine.State.LoadingModel,
                InferenceEngine.State.ModelReady,
            ),
        )
        assertNull("没见过提示词处理，绝不能认领", sim.key)

        assertFalse(
            shouldClaimInFlight(
                InferenceEngine.State.ModelReady,
                promptDispatched = false,
                hasInFlight = true,
                hasKey = false,
            ),
        )
    }

    // ---------- 场景二~四：不重复认领 / 出错不认领 ----------

    @Test
    fun `key 已有值 → 不重复认领`() {
        assertFalse(
            shouldClaimInFlight(
                InferenceEngine.State.ModelReady,
                promptDispatched = true,
                hasInFlight = true,
                hasKey = true,
            ),
        )

        val sim = MonitorSim(initialKey = "prompt-a")
        sim.run(listOf(InferenceEngine.State.ProcessingSystemPrompt, InferenceEngine.State.ModelReady))
        assertEquals("已有 key 原样保留", "prompt-a", sim.key)
        assertEquals(0, sim.claims)
    }

    @Test
    fun `中途出现 Error → 不认领`() {
        assertFalse(
            shouldClaimInFlight(
                InferenceEngine.State.Error(IllegalStateException("boom")),
                promptDispatched = true,
                hasInFlight = true,
                hasKey = false,
            ),
        )

        val sim = MonitorSim()
        sim.run(
            listOf(
                InferenceEngine.State.ProcessingSystemPrompt,
                InferenceEngine.State.Error(IllegalStateException("boom")),
            ),
        )
        assertNull("Error 状态下不许认领", sim.key)
        assertEquals(0, sim.claims)
    }

    @Test
    fun `没有在途提示词 → 不认领`() {
        assertFalse(
            shouldClaimInFlight(
                InferenceEngine.State.ModelReady,
                promptDispatched = true,
                hasInFlight = false,
                hasKey = false,
            ),
        )
    }

    @Test
    fun `认领一次后复位——后续 ModelReady 不再重复认领`() {
        val sim = MonitorSim()
        sim.run(
            listOf(
                InferenceEngine.State.ProcessingSystemPrompt,
                InferenceEngine.State.ModelReady,
                InferenceEngine.State.ModelReady,
            ),
        )
        assertEquals("只认领一次", 1, sim.claims)
        assertTrue("认领后 key 必须有值", sim.key != null)
    }

    // ---------- 认领之后状态要翻成 Ready（设置页的可见结果） ----------

    @Test
    fun `认领后 sessionStateOf(ModelReady, true) == Ready`() {
        assertEquals(SessionState.Ready, sessionStateOf(InferenceEngine.State.ModelReady, loaded = true))
        // 认领发生前就是这个映射——设置页卡住的直接原因
        assertEquals(SessionState.Loading, sessionStateOf(InferenceEngine.State.ModelReady, loaded = false))
    }
}
