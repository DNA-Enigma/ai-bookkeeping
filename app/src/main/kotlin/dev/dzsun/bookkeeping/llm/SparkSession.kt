package dev.dzsun.bookkeeping.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * ## 两种输出形状靠「用户消息前缀」区分，不靠换提示词
 *  - [MODE_PARSE] → JSON **数组**（记账抽取；闲聊/查询/转账/退款输出 `[]`）
 *  - [MODE_CHAT]  → 能记账就给数组，否则给 `{"reply":"..."}`；查账类问题 reply 固定为「查账」，
 *                   由上层走**本地账本 SQL**，不让模型编数字。
 */
@Singleton
class SparkSession @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val engine by lazy { AiChat.getInferenceEngine(context) }

    /** 面板与批量评估直接用它拿流式正文；解析与对话走 [complete]。 */
    val llm = SparkLlm(engine)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 已加载的系统提示；null = 未加载。 */
    private var loadedKey: String? = null

    /**
     * 最近一次发起过 init 的提示词。
     *
     * 背景：`setSystemPrompt` 是**阻塞 JNI 调用**，调用方超时只能取消协程、停不掉底层。
     * 底层会继续把提示词处理完并进入 ModelReady——不记下来的话，下一次 ensureReady 会以为
     * 没加载过、又去 loadModel，撞上状态机的 `check(Initialized)` 直接抛，于是**永远失败**。
     * 首轮批量评估就是这么把 35 条全打成规则兜底的。
     */
    private var inFlightPrompt: String? = null

    /** 应用默认科目表的支出/收入分类名：进系统提示，也是解析时的白名单。 */
    val expenseCategories: List<String> by lazy { loadAccounts("expense") }
    val incomeCategories: List<String> by lazy { loadAccounts("income") }

    /** 全 App 唯一的系统提示（懒算一次）。所有调用方都用它，保证不会触发重载。 */
    val systemPrompt: String by lazy { buildSystemPrompt(expenseCategories, incomeCategories) }

    val isReady: Boolean get() = loadedKey != null

    val state get() = engine.state

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
     * @return false = 找不到模型或加载失败（调用方走降级路径，不要抛给用户）
     */
    suspend fun ensureReady(): Boolean {
        val prompt = systemPrompt
        if (loadedKey == prompt) return true
        return mutex.withLock {
            if (loadedKey == prompt) return@withLock true

            // 上次只是调用方先超时、JNI 后台其实跑完了 → 直接接上
            if (inFlightPrompt == prompt &&
                engine.state.value is com.arm.aichat.InferenceEngine.State.ModelReady
            ) {
                loadedKey = prompt
                return@withLock true
            }

            val file = findModel() ?: return@withLock false
            try {
                // 引擎是进程级单例，可能停在 Error/ModelReady 上；能复位就复位，
                // 复位不了（比如 JNI 还在跑）就让它失败，下次靠上面那条接上。
                if (engine.state.value !is com.arm.aichat.InferenceEngine.State.Initialized) {
                    runCatching { llm.free() }
                }
                inFlightPrompt = prompt
                llm.init(file.absolutePath, prompt)
                loadedKey = prompt
                true
            } catch (e: Throwable) {
                Log.w(TAG, "init 失败: ${e.message}", e)
                loadedKey = null
                false
            }
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

    fun unload() {
        runCatching { llm.free() }
        loadedKey = null
        inFlightPrompt = null
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

    private fun buildSystemPrompt(expense: List<String>, income: List<String>): String = buildString {
        append("你是「阿账」，一个运行在用户设备**本地**的记账管家，用简体中文，简短直接、不废话。\n")
        append("你会收到两种输入，输出格式严格二选一，不要输出 JSON 之外的任何文字、解释或 markdown。\n\n")
        append("A. 输入以【记账解析】开头：只输出 JSON **数组**，元素为\n")
        append("   {\"kind\":\"expense\"或\"income\",\"amount\":数字(元,最多两位小数),")
        append("\"category\":\"分类名\",\"note\":\"不超过10字的备注\",\"payee\":\"商户,没有就空串\",")
        append("\"confidence\":0到1}\n")
        append("   不是真实消费/收入（闲聊、查询统计、转账、退款）时输出 []。\n")
        append("   分类只能从这里选，不得自造：\n")
        append("     支出：").append(expense.joinToString("、")).append("\n")
        append("     收入：").append(income.joinToString("、")).append("\n")
        append("   多笔合并拆成多个元素；中文数字转阿拉伯（五十块→50）；单位统一元。\n\n")
        append("B. 输入以【对话】开头：分两种情况\n")
        append("   - 用户其实在报一笔账 → 同样输出 A 的 JSON 数组（上层会弹出记账卡片）\n")
        append("   - 否则输出 {\"reply\":\"你的回答\"}，60 字以内，**不编造具体金额、比例和统计数字**；\n")
        append("     用户在问「花了多少」「哪类最多」这类要查账的问题，reply 一律只写「查账」\n")
        append("     （上层会去查本地账本，不会让你算数）。\n")
    }

    companion object {
        private const val TAG = "SparkSession"

        const val DEFAULT_MAX_TOKENS = 192

        /** 记账解析模式的用户消息前缀。 */
        const val MODE_PARSE = "【记账解析】\n"

        /** 阿账对话模式的用户消息前缀。 */
        const val MODE_CHAT = "【对话】\n"
    }
}
