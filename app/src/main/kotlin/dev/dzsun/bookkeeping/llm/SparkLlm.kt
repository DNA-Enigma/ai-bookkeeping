package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 端侧推理的最小可用封装 —— 把官方 `InferenceEngine` 收敛成三件事：
 * `init`（加载模型 + 立刻设系统提示）→ `generate`（拿流式正文）→ `free`（卸载）。
 *
 * 为什么要有这层：
 *  1. 官方接口要求「系统提示必须紧跟在 loadModel 之后、且只此一次」，
 *     这个时序约束放在外面迟早有人踩，这里收成一个原子的 [init]。
 *  2. 模型会吐思考段，UI 不该看到 —— [generate] 出来的流已经过 [ThinkingStripper]。
 *
 * 线程：底层跑在 `InferenceEngineImpl` 自己的单线程 dispatcher 上，
 * 本类不额外起线程，调用方按普通 Flow 收集即可。
 */
class SparkLlm(private val engine: InferenceEngine) {

    /** 引擎状态：加载中 / ModelReady / Generating / Error 都从这里透出。 */
    val state: Flow<InferenceEngine.State> get() = engine.state

    /**
     * 加载模型并设置系统提示。两步之间不可插入别的调用（底层状态机如此）。
     *
     * @throws java.io.FileNotFoundException 模型路径不存在
     * @throws com.arm.aichat.UnsupportedArchitectureException 模型架构不被支持
     */
    suspend fun init(
        modelPath: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ) {
        engine.loadModel(modelPath)
        engine.setSystemPrompt(systemPrompt)
    }

    /**
     * 发起一轮对话，返回**已剔除思考段**的正文流。
     * 逐块发射，收集方可以边收边渲染。
     */
    fun generate(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Flow<String> = flow {
        require(prompt.isNotBlank()) { "prompt 不能为空" }
        val stripper = ThinkingStripper()
        engine.sendUserPrompt(prompt, maxTokens).collect { token ->
            val text = stripper.feed(token)
            if (text.isNotEmpty()) emit(text)
        }
        val tail = stripper.flush()
        if (tail.isNotEmpty()) emit(tail)
    }

    /** 一次性拿完整回答（诊断、脚本化验证用）。 */
    suspend fun generateText(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): String = buildString { generate(prompt, maxTokens).collect { append(it) } }.trim()

    /** 卸载模型、释放内存。可再次 [init]。 */
    fun free() = engine.cleanUp()

    companion object {
        const val DEFAULT_MAX_TOKENS = 512

        /**
         * 系统提示。模型的 chat_template 会把它包成 `<|System|>` 回合，
         * 所以这里给**纯文本**就好，不要自己拼模板标记。
         *
         * 要求「直接作答、不输出思考过程」——即便 [ThinkingStripper] 会兜底剔除，
         * 提示词层面先省掉思考 token，端侧能省不少时延。
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个运行在用户设备本地的记账助手。直接给出最终答案，不要输出思考过程；" +
                "需要给结构化数据时只输出 JSON 本身，不要用代码围栏包裹；用简体中文回答。"
    }
}
