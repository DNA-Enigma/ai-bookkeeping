package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 流式产出的一块。
 *
 * ## 为什么要分两条通道，而不是把思考原文混进正文里
 *
 * 需求 C 要的是「思考过程」与「正文」**分开展示**：思考默认收起、正文进气泡。
 * 如果只给一个 `Flow<String>`，上层就只能自己再跑一遍剔除逻辑去猜哪段是思考——
 * 那等于把 [ThinkingStripper] 的规则复制到每个调用方。分通道之后，
 * 剔除规则仍然只有一处（[ThinkingStripper]），调用方只做展示。
 */
sealed interface LlmChunk {
    val text: String

    /** 可展示的正文增量（已剔除思考段与控制 token）。 */
    data class Answer(override val text: String) : LlmChunk

    /**
     * 思考段原文 —— 从正文里剔掉了，但**不是垃圾**：
     * 它是「思考过程」折叠区的素材，也是排查模型为什么答错时唯一的现场。
     */
    data class Thought(override val text: String) : LlmChunk
}

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
    ): Flow<String> = generateChunks(prompt, maxTokens)
        .filterIsInstance<LlmChunk.Answer>()
        .map { it.text }

    /**
     * 发起一轮对话，**思考段与正文分通道**逐块发射 —— 悬浮面板/对话页的
     * 「思考过程」折叠区与正文气泡都吃这一条。
     *
     * ## 为什么思考内容走队列而不是直接回调里 emit
     *
     * [ThinkingStripper] 的 `onThought` 是在 [InferenceEngine.sendUserPrompt] 的
     * collect 内部被同步调用的。在 `flow {}` 里**从回调里直接 `emit`** 会踩
     * 「Flow invariant is violated：从另一个协程发射」——收不收得到取决于上游
     * 跑在哪个 context，属于偶发崩溃。所以回调只把文本塞进本地队列，
     * 由外层 collect 循环按序发射。
     *
     * 顺序保证：思考段总是排在它后面那段正文之前（时间顺序），
     * 折叠区因此不会出现「先看到答案再看到推理」。
     */
    fun generateChunks(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Flow<LlmChunk> = flow {
        require(prompt.isNotBlank()) { "prompt 不能为空" }
        val thoughts = ArrayDeque<String>()
        val stripper = ThinkingStripper { thoughts.addLast(it) }
        engine.sendUserPrompt(prompt, maxTokens).collect { token ->
            val text = stripper.feed(token)
            while (thoughts.isNotEmpty()) emit(LlmChunk.Thought(thoughts.removeFirst()))
            if (text.isNotEmpty()) emit(LlmChunk.Answer(text))
        }
        val tail = stripper.flush()
        while (thoughts.isNotEmpty()) emit(LlmChunk.Thought(thoughts.removeFirst()))
        if (tail.isNotEmpty()) emit(LlmChunk.Answer(tail))
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
