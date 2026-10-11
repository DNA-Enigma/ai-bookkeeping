package dev.dzsun.bookkeeping.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [SparkSession.ensureReady] 的结果：要么就绪，要么带一条能落到界面上的原因。
 *
 * 用封闭类型而不是 Boolean，是因为「没就绪」在界面上必须说清楚**是什么、下一步做什么**——
 * 布尔值会把原因丢在 logcat 里，用户看到的文案就只能靠猜。
 */
sealed interface ReadyResult {
    data object Ready : ReadyResult
    data class Failed(val issue: ModelIssue) : ReadyResult
}

/** 模型没就绪的原因。封闭成三类，调用方的 `when` 漏一类编译器就会拦。 */
sealed interface ModelIssue {
    /** 设备上找不到 GGUF 文件。 */
    data object NoModelFile : ModelIssue

    /** 加载模型或解码系统提示时抛了错（保留原始报错文本）。 */
    data class LoadFailed(val message: String) : ModelIssue

    /**
     * 引擎状态机拒绝了本次调用（上一轮没卸干净、还在生成、超时残留）。
     * [engineState] 是拒绝时刻的引擎状态名 —— 这是「状态卡死」的现场证据。
     */
    data class Interrupted(val engineState: String) : ModelIssue
}

/**
 * 把 [ModelIssue] 翻成给用户看的中文：**先说清是什么，再说下一步做什么**。
 *
 * 纯函数：不碰引擎、不打日志、不读资源，所以能直接单测各分支。
 */
fun issueMessage(issue: ModelIssue): String = when (issue) {
    ModelIssue.NoModelFile -> "未找到模型文件，去设置页点「加载模型」"
    is ModelIssue.LoadFailed ->
        "模型加载失败：${issue.message}。点「卸载」后重新加载，或重启应用再试"
    is ModelIssue.Interrupted ->
        "模型引擎状态异常（${issue.engineState}），点「卸载」后重新加载"
}

/**
 * 属主 [SparkSession] 对外的**权威状态**：全 App 订阅这一份，谁也不许自己再攒一份。
 *
 * 背景：设置页原先自持 `_state`，功能页把模型加载好了它还显示「未加载」，
 * 面板自动重载后它又倒回去——两份状态必然漂移。状态的真源只有属主一个。
 */
sealed interface SessionState {
    /** 没加载、也没在准备 —— 设置页该显示「未加载」。 */
    data object Idle : SessionState

    /** 加载中 / 解码系统提示中 / 卸载中（首次约 1~2 分钟）。 */
    data object Loading : SessionState

    /** 已就绪，可以生成。 */
    data object Ready : SessionState

    /** 正在生成。 */
    data object Generating : SessionState

    /** 引擎报错，[message] 是给用户看的原因。 */
    data class Error(val message: String) : SessionState
}

/**
 * 引擎状态 + 是否已加载提示词 → [SessionState]。纯函数，单测直接钉。
 *
 * 口径：
 *  - **Error 优先**：引擎报错时无论加载与否，先把原因亮出来；
 *  - 生成类状态（Generating / ProcessingUserPrompt）→ Generating；
 *  - 准备类状态（加载/解码提示/卸载/基准测试）→ Loading；
 *  - ModelReady 且提示词已加载 → Ready；ModelReady 但没加载：调用方取消后由属主自愈认领
 *    （见 [shouldClaimInFlight]），未认领前按 Loading 展示；
 *  - 其余（Uninitialized / Initialized）：没加载就是 Idle，
 *    加载了却回到这里说明引擎被复位，按 Loading 算、等下一次 ensureReady 接上。
 */
internal fun sessionStateOf(engineState: InferenceEngine.State, loaded: Boolean): SessionState =
    when (engineState) {
        is InferenceEngine.State.Error -> SessionState.Error(
            engineState.exception.message ?: engineState.exception.javaClass.simpleName,
        )

        InferenceEngine.State.Generating,
        InferenceEngine.State.ProcessingUserPrompt,
        -> SessionState.Generating

        InferenceEngine.State.Initializing,
        InferenceEngine.State.LoadingModel,
        InferenceEngine.State.UnloadingModel,
        InferenceEngine.State.ProcessingSystemPrompt,
        InferenceEngine.State.Benchmarking,
        -> SessionState.Loading

        InferenceEngine.State.ModelReady -> if (loaded) SessionState.Ready else SessionState.Loading

        else -> if (loaded) SessionState.Loading else SessionState.Idle
    }

/**
 * 自愈监视器的置位条件：引擎进入了**系统提示处理**阶段。
 *
 * 这是「提示词真正挂上过」的唯一证据 —— 监视协程看到它就把 `promptDispatched`
 * 置 true，之后的那个 ModelReady 才有资格被认领（见 [shouldClaimInFlight]）。
 */
internal fun isPromptDispatching(engineState: InferenceEngine.State): Boolean =
    engineState is InferenceEngine.State.ProcessingSystemPrompt

/**
 * 自愈监视器的判定：这个引擎状态下，该不该替被取消的调用方认领在途提示词。
 *
 * 场景（2026-10-10 模拟器实测）：调用方在 `llm.init` 还阻塞在 JNI 时被取消
 * （对话页按返回，承载 [SparkSession.ensureReady] 的协程一起没），
 * 底层不受取消影响照常跑完，但 `loadedKey` 的赋值语句随协程消失 ——
 * 引擎 ModelReady、key 却恒为 null，[sessionStateOf] 长期停在 Loading，
 * 设置页永远显示「正在加载模型」，实际模型此刻完全可用。
 *
 * 三条缺一不可：
 *  - `promptDispatched`：**必须先看过 `ProcessingSystemPrompt`**。ModelReady 有两种来路：
 *    a) `loadModel` 刚完成、`setSystemPrompt` 还没开始 —— 提示词没挂上，认领等于谎报就绪；
 *    b) 系统提示处理完 —— 可以认领。
 *    所以**不许**只凭「状态是 ModelReady 且 key 为空」就认领，这个标志就是来路的证据。
 *  - `hasInFlight`：确实有一条在途提示词，没有就无从认领。
 *  - `!hasKey`：key 还空着 —— 已有值说明属主或下一次 [SparkSession.ensureReady] 已经记过，
 *    不重复认领。
 *
 * 中途进过 [InferenceEngine.State.Error] 也一样：Error 不是 ModelReady，不认领。
 *
 * 纯函数：监视协程只做副作用，判定全在这里，单测直接钉。
 */
internal fun shouldClaimInFlight(
    engineState: InferenceEngine.State,
    promptDispatched: Boolean,
    hasInFlight: Boolean,
    hasKey: Boolean,
): Boolean = engineState is InferenceEngine.State.ModelReady &&
    promptDispatched && hasInFlight && !hasKey

/**
 * 端侧模型的**唯一属主**：找模型、加载、系统提示、生成、卸载。
 *
 * ## 为什么全 App 只能有一个系统提示
 * 官方引擎要求 `setSystemPrompt` **紧跟 loadModel、且只此一次**，想换就得重新加载模型并
 * 重新解码提示词（模拟器实测 65 秒）。而要用模型的地方有三处：
 * 记一笔解析（SparkAiParser）、阿账对话（AzhangChat）、设置页面板。
 * 各写各的提示词就会来回重载，界面卡到没法用。
 *
 * 所以提示词在这里**算一次**（含从科目表读来的分类枚举），三个调用方一律用 [systemPrompt]，
 * [ensureReady] 不带参数 —— **同一个字符串，永不重载**。
 *
 * ## 三种输出形状靠「用户消息前缀」区分，不靠换提示词
 *  - [MODE_PARSE] → JSON **数组**（记账抽取；闲聊/查询/转账/退款输出 `[]`）
 *  - [MODE_CHAT]  → 能记账就给数组，否则给 `{"reply":"..."}`；查账类问题 reply 固定为「查账」，
 *                   由上层走**本地账本 SQL**，不让模型编数字。
 *  - [MODE_QUERY] → 问账判定：`{"intent":"query",…}` 或 `{"intent":"chat"}`。
 *                   **时间词只出符号名**（`this_month`），日期换算在本地做——
 *                   让模型算日期实测必错（服务端同一类 bug：提示词没给今天几号，
 *                   「这个月」被算成上个月）。
 */
@Singleton
class SparkSession @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val engine by lazy { AiChat.getInferenceEngine(context) }

    /** 面板与批量评估直接用它拿流式正文；解析与对话走 [complete]。 */
    val llm = SparkLlm(engine)

    private val mutex = Mutex()

    /**
     * 串行化 `llm.init / llm.free` 的专用锁 —— **只防并发 init，不负责界面响应**。
     *
     * 与 [mutex] 分工见 [ensureReady] 的「锁的两段式」注释：init 要跑 267 秒，
     * 塞进 mutex 会冻住所有状态查询；但完全不锁又会让第二个调用方 free 掉
     * 第一个正在跑的 init。锁序恒为 initMutex → mutex。
     */
    private val initMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 已加载的系统提示；null = 未加载。写它即等于对外广播「加载好了 / 卸掉了」。 */
    private val _loadedKey = MutableStateFlow<String?>(null)
    private var loadedKey: String?
        get() = _loadedKey.value
        set(value) {
            _loadedKey.value = value
        }

    /**
     * 最近一次发起过 init 的提示词。
     *
     * 背景：`setSystemPrompt` 是**阻塞 JNI 调用**，调用方超时只能取消协程、停不掉底层。
     * 底层会继续把提示词处理完并进入 ModelReady——不记下来的话，下一次 ensureReady 会以为
     * 没加载过、又去 loadModel，撞上状态机的 `check(Initialized)` 直接抛，于是**永远失败**。
     * 首轮批量评估就是这么把 35 条全打成规则兜底的。
     *
     * 自愈监视器（另一个协程）也读它，所以 @Volatile。
     */
    @Volatile
    private var inFlightPrompt: String? = null

    /**
     * 是否已观察到引擎进入 `ProcessingSystemPrompt` —— 即**本轮提示词真正挂上过**。
     *
     * 每轮 [ensureReady] 发起 init 前置 false；看过 `ProcessingSystemPrompt` 置 true；
     * 自愈认领后复位 false。它是 [shouldClaimInFlight] 里区分「loadModel 刚完、提示词还没开始」
     * 与「提示词处理完了」两种 ModelReady 的唯一证据。自愈监视器会跨协程写它，所以 @Volatile。
     */
    @Volatile
    private var promptDispatched = false

    /**
     * 自愈监视器的作用域：进程级，与本 @Singleton 同寿，不随任何调用方的导航/超时取消。
     *
     * 刻意用裸 `SupervisorJob + Dispatchers.Default`，不引 Hilt 的 ApplicationScope ——
     * 这是属主自己的内部机制，不欠任何注入图的债。
     */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        monitorScope.launch {
            engine.state.collect { s ->
                if (isPromptDispatching(s)) promptDispatched = true
                val prompt = inFlightPrompt
                if (prompt != null &&
                    shouldClaimInFlight(s, promptDispatched, hasInFlight = true, hasKey = loadedKey != null)
                ) {
                    // 自愈认领也加锁，避免与 ensureReady 并发写状态
                    mutex.withLock {
                        if (loadedKey == null) {
                            loadedKey = prompt
                            promptDispatched = false
                            Log.i(TAG, "调用方已取消，属主自行认领提示词（engine=$s）")
                        }
                    }
                }
            }
        }
    }

    /** 应用默认科目表的支出/收入分类名：进系统提示，也是解析时的白名单。 */
    val expenseCategories: List<String> by lazy { loadAccounts("expense") }
    val incomeCategories: List<String> by lazy { loadAccounts("income") }

    /** 全 App 唯一的系统提示（懒算一次）。所有调用方都用它，保证不会触发重载。 */
    val systemPrompt: String by lazy { buildSystemPrompt(expenseCategories, incomeCategories) }

    val isReady: Boolean get() = loadedKey != null

    val state get() = engine.state

    /**
     * 属主的权威状态流 —— **设置页等消费方只订阅它，不许自持一份状态**。
     *
     * 由引擎状态机与 `loadedKey` 合成（不重建引擎的状态机，只做翻译，
     * 见 [sessionStateOf]）。功能页加载 / 面板自动重载 / 设置页卸载，
     * 订阅方都会收到同一次变化，不存在两份状态打架。
     */
    val sessionState: Flow<SessionState> by lazy {
        combine(engine.state, _loadedKey) { s, key -> sessionStateOf(s, key != null) }
            .distinctUntilChanged()
    }

    /** 找设备上的 GGUF：优先外部私有目录，再退回内部存储（内置包会释放到 files/models）。 */
    fun findModel(): File? {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            File(context.filesDir, "models"),
            context.getExternalFilesDir("Download"),
        )
        for (d in dirs) {
            d.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("gguf", ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    /**
     * 确保模型已加载。幂等：同一个系统提示只会真正加载一次。
     *
     * 失败**不抛**（协程取消除外）——一律折进 [ReadyResult.Failed]，原因分
     * [ModelIssue.NoModelFile] / [ModelIssue.LoadFailed] / [ModelIssue.Interrupted] 三类，
     * 调用方自己决定降级还是把 [issueMessage] 摆到界面上。
     *
     * **锁的两段式**（并发口径，改这段前先读完）：
     *  - [mutex] 只包「检查-赋值」这类微秒级段落，**绝不跨 267 秒的 JNI init**——
     *    否则一次加载会把面板状态查询、设置页刷新全冻住；
     *  - [initMutex] 把真正的 `llm.init/free` **串行化**：第二个调用方在这里排队，
     *    排完先重查状态（前一个可能已完成），**不许 free 掉别人正在跑的 init、
     *    也不许并发两次 init**——引擎状态机对 loadModel 的 `check(Initialized)`
     *    在并发下会互踩，症状是「加载成功但其实无效」或原生层崩。
     *  - 锁序恒为 initMutex → mutex，任何路径不得反向嵌套。
     */
    suspend fun ensureReady(): ReadyResult {
        val prompt = systemPrompt

        // 快速路径：已加载 / 上一轮 init 其实跑完了（调用方超时但引擎已 ModelReady）
        mutex.withLock {
            if (loadedKey == prompt) return ReadyResult.Ready
            if (inFlightPrompt == prompt &&
                engine.state.value is com.arm.aichat.InferenceEngine.State.ModelReady
            ) {
                loadedKey = prompt
                return ReadyResult.Ready
            }
        }

        val file = findModel() ?: return ReadyResult.Failed(ModelIssue.NoModelFile)

        // 真正的 init 串行化。锁内只做微秒级状态准备，JNI 长调用在 initMutex 保护下
        // 但仍不占 mutex —— 两个目标（不冻界面 × 不并发 init）同时满足。
        initMutex.withLock {
            // 排队期间前一个可能已经完成 → 重查一次，完成了就直接复用
            mutex.withLock {
                if (loadedKey == prompt) return ReadyResult.Ready
                if (inFlightPrompt == prompt &&
                    engine.state.value is com.arm.aichat.InferenceEngine.State.ModelReady
                ) {
                    loadedKey = prompt
                    return ReadyResult.Ready
                }
            }
            mutex.withLock {
                if (engine.state.value !is com.arm.aichat.InferenceEngine.State.Initialized) {
                    runCatching { llm.free() }
                }
                inFlightPrompt = prompt
                // 本轮从头来过：先撤销上一轮的「提示词挂上过」证据，
                // 等真观察到 ProcessingSystemPrompt 再立起来（防止旧证据让监视器误认领）
                promptDispatched = false
            }

            return try {
                llm.init(file.absolutePath, prompt)
                mutex.withLock {
                    loadedKey = prompt
                }
                ReadyResult.Ready
            } catch (e: CancellationException) {
                // 取消要照常抛出去，否则 withTimeoutOrNull 会以为这轮正常结束了
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "init 失败: ${e.message}", e)
                mutex.withLock {
                    loadedKey = null
                }
                ReadyResult.Failed(classifyInitFailure(e))
            }
        }
    }

    /**
     * 把引擎抛的异常归类。
     *
     * 引擎状态机的 `check` 拒绝（loadModel 要求 Initialized、cleanUp 只认
     * ModelReady/Error、setSystemPrompt 要求紧跟 loadModel）算 [ModelIssue.Interrupted]，
     * 并记下当前状态名；其余（文件缺失、架构不支持、准备资源失败、提示词解码失败）
     * 算 [ModelIssue.LoadFailed]。
     */
    private fun classifyInitFailure(e: Throwable): ModelIssue {
        val msg = e.message ?: e.javaClass.simpleName
        val stateRejected = e is IllegalStateException && STATE_REJECTION_MARKERS.any { msg.contains(it) }
        return if (stateRejected) {
            ModelIssue.Interrupted(engine.state.value.javaClass.simpleName)
        } else {
            ModelIssue.LoadFailed(msg)
        }
    }

    /** 模型已就绪时生成正文；未就绪抛 [IllegalStateException]。 */
    suspend fun complete(userPrompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): String {
        check(loadedKey != null) { "模型未加载" }
        return llm.generateText(userPrompt, maxTokens)
    }

    /**
     * 流式生成：原样吐 token，**不聚合** —— 悬浮面板要逐字上屏就走这条。
     *
     * 未就绪会抛 [IllegalStateException]；超时与取消交给调用方包一层
     * `withTimeoutOrNull { ... .collect { } }`，**不要在 Flow 内部再套超时**，
     * 否则会触发「从另一个协程发射」的 Flow 不变量异常。
     */
    fun stream(userPrompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Flow<String> {
        check(loadedKey != null) { "模型未加载" }
        return llm.generate(userPrompt, maxTokens)
    }

    /**
     * 流式生成（**思考段与正文分通道**）。「思考过程」折叠区 + 正文气泡走这条。
     *
     * 与 [stream] 一样：未就绪抛 [IllegalStateException]，超时/取消由调用方包
     * `withTimeoutOrNull { ... .collect { } }`，**不要在 Flow 内部再套超时**。
     */
    fun streamChunks(userPrompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Flow<LlmChunk> {
        check(loadedKey != null) { "模型未加载" }
        return llm.generateChunks(userPrompt, maxTokens)
    }

    fun unload() {
        runCatching { llm.free() }
        // unload 是**非挂起**函数，不能在这里拿 suspend 锁（patch 原样会编译不过）。
        // 三个状态写都落在 MutableStateFlow / @Volatile 字段上，单次写入本身是原子的；
        // 失去的只是「和 ensureReady 的检查段排开」这一层保护，最坏窗口是
        // 一个协程刚判完 loadedKey==prompt 就返回，随后 generate 因引擎已 free
        // 抛 IllegalStateException —— 调用方本来就要接这个降级路径，不会崩、
        // 也不会对外谎报「已加载」（loadedKey 这里已置 null，sessionState 立刻广播）。
        loadedKey = null
        inFlightPrompt = null
        promptDispatched = false
    }

    private fun loadAccounts(kind: String): List<String> = runCatching {
        val text = context.assets.open("default_accounts.json")
            .bufferedReader().use { it.readText() }
        val doc = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching emptyList()
        (doc["accounts"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.content
            val name = o["name"]?.jsonPrimitive?.content
            if (type != null && name != null && type.equals(kind, true)) name else null
        } ?: emptyList()
    }.getOrElse {
        Log.w(TAG, "读取科目表失败: ${it.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "SparkSession"

        /**
         * 引擎状态机拒绝时抛出的消息片段 —— 对应 `InferenceEngineImpl` 里
         * loadModel / cleanUp / setSystemPrompt 三处 `check`（上游原样引入，不可改）。
         * 命中其中任一条就是「状态卡死」，而不是模型本身有问题。
         */
        private val STATE_REJECTION_MARKERS = listOf(
            "Cannot load model in",
            "Cannot unload model in",
            "Cannot process system prompt in",
            "System prompt must be set",
        )

        const val DEFAULT_MAX_TOKENS = 192

        /**
         * **初始化预算的唯一定义处**：加载模型 + 解码系统提示。
         *
         * 240s 来自实测 —— 模拟器上冷启动的系统提示解码 97~149s（见软测 TC-21/TC-03），
         * 取它再留余量。面板、对话页、问账页一律引用这个常量，
         * 不许再各写各的（此前 90s / 45s / 20s 三套预算互相打架，实测必超）。
         * 与 `SparkAiParser.INIT_TIMEOUT_MS` 同值，由单测钉住。
         */
        const val INIT_BUDGET_MS = 240_000L

        /**
         * **单轮生成预算的唯一定义处**。
         *
         * 实测单轮生成 16~46s（批测平均 37.7s），90s 覆盖最慢的一倍有余。
         * 同样是三个入口共用，别再写死 45_000 / 20_000。
         */
        const val GEN_BUDGET_MS = 90_000L

        /** 记账解析模式的用户消息前缀。 */
        const val MODE_PARSE = "【记账解析】\n"

        /** 阿账对话模式的用户消息前缀。 */
        const val MODE_CHAT = "【对话】\n"

        /**
         * 问账判定模式的用户消息前缀。
         *
         * 与 [MODE_CHAT] 分开是因为**输出契约不同**：对话模式的查账信号是一个
         * 字符串常量 `查账`（模型自己判断要不要查），而这里是「不管查不查，
         * 先把意图和条件结构化出来」——问账页要拿它去跑本地 SQL。
         */
        const val MODE_QUERY = "【问账判定】\n"
    }
}

/**
 * 全 App 唯一的系统提示（[SparkSession.systemPrompt] 懒算一次的那一份）。
 *
 * 抽成顶层函数是为了**能直接单测 few-shot 段落**：B 段的判据与例子就是对话模式下
 * 「报账 → 数组 / 查账 → reply=查账 / 闲聊 → reply」的分流规则本身，
 * 测不到的提示词等于没写 —— 实测里它只有一句话时模型 6 次含金额输入全走成了闲聊。
 */
internal fun buildSystemPrompt(expense: List<String>, income: List<String>): String = buildString {
    append("你是「阿账」，一个运行在用户设备**本地**的记账管家，用简体中文，简短直接、不废话。\n")
    append("你会收到三种输入，各自对应一种输出格式，不要输出 JSON 之外的任何文字、解释或 markdown。\n\n")
    append("A. 输入以【记账解析】开头：只输出 JSON **数组**，元素为\n")
    append("   {\"kind\":\"expense\"或\"income\",\"amount\":数字(元,最多两位小数),")
    append("\"category\":\"分类名\",\"note\":\"不超过10字的备注\",\"payee\":\"商户,没有就空串\",")
    append("\"confidence\":0到1}\n")
    append("   不是真实消费/收入（闲聊、查询统计、转账、退款）时输出 []。\n")
    append("   分类只能从这里选，不得自造：\n")
    append("     支出：").append(expense.joinToString("、")).append("\n")
    append("     收入：").append(income.joinToString("、")).append("\n")
    append("   多笔合并拆成多个元素；中文数字转阿拉伯（五十块→50）；单位统一元。\n")
    append("   **amount 必须与用户原话里的数字完全一致**：原话是 20 就写 20，是 3.5 就写 3.5。\n")
    append("   不许四舍五入、不许改成整数、不许自己补一个数——**记错金额比记不上更糟**。\n\n")
    append("B. 输入以【对话】开头：\n")
    append("   - 只输出 JSON，不要 JSON 之外的任何文字、解释或 markdown。\n")
    append("   - **判据（先看这条再选格式）**：句子里出现了**具体金额**和**买了什么**，")
    append("用户就是在报一笔账 → 输出 A 的 JSON 数组（上层会弹出记账卡片）；\n")
    append("     只是在问「花了多少」「哪类最多」这类要统计的 → 查账；除此之外才是说话。\n")
    append("   - **报账的说法很多，下面这些全都输出数组，不是闲聊**：\n")
    append("     商户+金额：「哈啰单车3.5元」「午餐20元」「打车28元」「星巴克咖啡32元」「便利店15.5」\n")
    append("     动词+金额：「买咖啡用了18」「充话费100」「交房租3500」\n")
    append("     **注意**：只要句子里有一个金额、又说了买了什么或在哪儿花的，就是报账。\n")
    append("     不要因为句子短、没有「花」字、或看不懂那个商户名就转去闲聊回答（例如把")
    append("「哈啰单车3.5元」答成「哈啰单车是共享单车功能」——那是错的分流）。\n")
    append("   - 三组例子，照着来（同样是这三类，别再自己发挥）：\n")
    append("     报账：「午饭花了 35」 → ")
    append("[{\"kind\":\"expense\",\"amount\":35,\"category\":\"餐饮\",\"note\":\"午饭\",\"payee\":\"\",\"confidence\":0.9}]\n")
    append("     查账：「这个月花了多少」「哪类最多」 → {\"reply\":\"查账\"}\n")
    append("     说话：「你好」「在吗」 → {\"reply\":\"你好，我是阿账，想记一笔就说一声。\"}\n")
    append("   - 报账时 amount **必须与用户原话里的数字完全一致**，不许四舍五入、不许改数、")
    append("不许自己补一个数（原话「午餐20元」就写 20，写 30 是严重错误）。\n")
    append("   - 否则输出 {\"reply\":\"你的回答\"}，60 字以内，**不编造具体金额、比例和统计数字**；\n")
    append("     用户在问「花了多少」「哪类最多」这类要查账的问题，reply 一律只写「查账」\n")
    append("     （上层会去查本地账本，不会让你算数）。\n")
    append("   - 输入是问候、闲聊、英文、或你看不懂的内容，**也必须**输出 {\"reply\":\"...\"}，\n")
    append("     用一句自然的中文回应（打个招呼，或请对方说清楚想记什么）。\n")
    append("   - **绝不输出**括号说明、道歉或占位文本（例如「（暂无具体对话内容）」「无法回答」）。\n")
    append("     说不出口的话就写进 reply 里，不要写在 JSON 外面。\n\n")
    append(QUERY_SECTION)
    append("     支出：").append(expense.joinToString("、")).append("\n")
    append("     收入：").append(income.joinToString("、")).append("\n")
}

/**
 * 系统提示 C 段（问账判定）。
 *
 * 单独拎出来是为了能被单测逐条钉 —— 这一段就是「这句话算不算问账本」的判据本身。
 *
 * 两条刻意的口径：
 *
 * 1. **时间词只出符号名，不出日期**。「今天是几号」交给程序：模型算日期实测必错
 *    （服务端同款 bug：「这个月」被算成上个月），而符号名→日期是一段纯算术，
 *    本地做既准又能逐条单测。所以判据里明写「不要自己算日期」。
 * 2. **没提时间就写 `all`，不是 `this_month`**。默认给「这个月」会把
 *    「一共花了多少」静默答成本月，用户看到的数字少了一大截却没有任何提示 ——
 *    与 `LocalQueryTranslator` 不猜日期是同一条口径。
 */
private val QUERY_SECTION: String = buildString {
    append("C. 输入以【问账判定】开头：只判断这句话**是不是在问账本**，只输出**一个** JSON 对象。\n")
    append("   - 是问账本（花了多少、哪类最多、去了几次、几笔、收支多少）→ 输出\n")
    append("     {\"intent\":\"query\",\"range\":\"时间\",\"direction\":\"方向\",")
    append("\"subject\":\"对象\",\"subject_kind\":\"种类\",\"group_by\":\"分组\"}\n")
    append("   - 不是（闲聊、问候、要建议、报一笔账、问别的领域的事）→ 输出 {\"intent\":\"chat\"}\n")
    append("   - **只输出这一个 JSON 对象**，不要解释、不要 markdown、不要多余字段。\n")
    append("   - range 只能从下面**照抄**（左边是取值，右边是它的意思），")
    append("**不要自己算日期**，今天是几号由程序处理：\n")
    append("     today=今天 yesterday=昨天 day_before_yesterday=前天 this_week=本周 ")
    append("last_week=上周 this_month=这个月 last_month=上个月 this_year=今年 last_year=去年\n")
    append("     all=**没提时间**（「一共花了多少」就写 all，不要默认成这个月）\n")
    append("   - direction 只能是 expense（花了/支出；**没提方向就写 expense**）")
    append("| income（挣了/收入）| both（收支/结余/净额）\n")
    append("   - subject_kind 只能是 category（分类词，从下面收支表里挑）| merchant（店名/对方名字）")
    append("| none（没提）\n")
    append("   - subject 是**要算的那样东西的名字**；没提就写空串 \"\"\n")
    append("   - group_by 只能是 none | category（哪类最多）| merchant（哪家最多）| month（按月）\n")
    append("   - 例子（照抄形状）：\n")
    append("     「这个月花了多少」 → {\"intent\":\"query\",\"range\":\"this_month\",")
    append("\"direction\":\"expense\",\"subject\":\"\",\"subject_kind\":\"none\",\"group_by\":\"none\"}\n")
    append("     「哪类花得最多」 → {\"intent\":\"query\",\"range\":\"all\",\"direction\":\"expense\",")
    append("\"subject\":\"\",\"subject_kind\":\"none\",\"group_by\":\"category\"}\n")
    append("     「星巴克去了几次」 → {\"intent\":\"query\",\"range\":\"all\",\"direction\":\"expense\",")
    append("\"subject\":\"星巴克\",\"subject_kind\":\"merchant\",\"group_by\":\"none\"}\n")
    append("     「这个月餐饮花了多少」 → {\"intent\":\"query\",\"range\":\"this_month\",")
    append("\"direction\":\"expense\",\"subject\":\"餐饮\",\"subject_kind\":\"category\",\"group_by\":\"none\"}\n")
    append("     「去年一共挣了多少」 → {\"intent\":\"query\",\"range\":\"last_year\",")
    append("\"direction\":\"income\",\"subject\":\"\",\"subject_kind\":\"none\",\"group_by\":\"none\"}\n")
    append("     「给我省钱建议」 → {\"intent\":\"chat\"}\n")
    append("     「今天天气怎么样」 → {\"intent\":\"chat\"}（**只有时间词不算问账**）\n")
    append("   - 分类词表（认成 category 时 subject 必须原样取自这里）：\n")
}
