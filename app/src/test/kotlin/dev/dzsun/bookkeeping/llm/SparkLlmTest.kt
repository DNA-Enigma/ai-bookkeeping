package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用假引擎验证 [SparkLlm] 的三件事：
 * 时序（loadModel → setSystemPrompt 不能错）、流式收集、思考段剔除。
 * 不碰 JNI，纯 JVM 跑得飞快。
 */
class SparkLlmTest {

    /** 记录调用顺序的替身引擎。 */
    private class FakeEngine(private val tokens: List<String>) : InferenceEngine {
        val calls = mutableListOf<String>()
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

        override suspend fun loadModel(pathToModel: String) {
            calls += "loadModel:$pathToModel"
            _state.value = InferenceEngine.State.ModelReady
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            // 官方实现要求紧跟 loadModel 且状态为 ModelReady
            assertTrue("setSystemPrompt 必须在 loadModel 之后", calls.last().startsWith("loadModel"))
            assertEquals(InferenceEngine.State.ModelReady, _state.value)
            calls += "setSystemPrompt"
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            calls += "sendUserPrompt"
            tokens.forEach { emit(it) }
        }

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = "bench"
        override fun cleanUp() { calls += "cleanUp" }
        override fun destroy() { calls += "destroy" }
    }

    @Test
    fun `init 先加载模型再设系统提示`() = runTest {
        val engine = FakeEngine(emptyList())
        val llm = SparkLlm(engine)
        llm.init("/data/models/spark.gguf")
        assertEquals(listOf("loadModel:/data/models/spark.gguf", "setSystemPrompt"), engine.calls)
    }

    @Test
    fun `输出里的思考段被剔除 只剩正文`() = runTest {
        val ts = "<" + "think>"
        val te = "<" + "/" + "think>"
        val engine = FakeEngine(listOf("打算", ts, "内部推理不要外泄", te, "打车 28 元"))
        val out = SparkLlm(engine).generate("这笔账怎么记").toList().joinToString("")
        assertEquals("打算打车 28 元", out)
    }

    @Test
    fun `生成的文本末尾不留空白`() = runTest {
        val engine = FakeEngine(listOf("你好", "\n\n"))
        val out = SparkLlm(engine).generateText("打个招呼")
        assertEquals("你好", out)
    }

    @Test
    fun `空 prompt 直接拒绝`() = runTest {
        val engine = FakeEngine(listOf("x"))
        try {
            SparkLlm(engine).generate("   ").toList()
            throw AssertionError("空 prompt 应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 期望的分支
        }
    }
}
