package dev.dzsun.bookkeeping.llm

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 端侧模型的**唯一属主**：找模型、加载、设系统提示、生成、卸载。
 *
 * 为什么要单独抽一层：
 *  - 官方 `InferenceEngine` 是进程级单例，但「系统提示必须紧跟 loadModel 且只此一次」
 *    是状态机约束——多处各自 init 就会互相踩（面板点一次、解析器又点一次）。
 *  - 系统提示里要带**用户的分类表**（分类是数据不是代码），换分类表就得重新加载。
 *    所以 [ensureReady] 比对提示词，不一致就重建。
 *
 * 三处调用方都走这里：设置页面板、[dev.dzsun.bookkeeping.feature.entry.SparkAiParser]、
 * debug 的批量评估入口。谁都不许自己 new `InferenceEngine`。
 */
@Singleton
class SparkSession @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val engine by lazy { AiChat.getInferenceEngine(context) }

    /** 面板与批量评估直接用它拿流式正文；解析器走 [complete]。 */
    val llm = SparkLlm(engine)

    private val mutex = Mutex()

    /** 当前加载的模型路径 + 所用系统提示。null = 未加载。 */
    private var loadedKey: String? = null

    /**
     * 最近一次发起过 init 的提示词。
     *
     * 背景：`setSystemPrompt` 是**阻塞 JNI 调用**，调用方超时只能取消协程、
     * 停不掉底层。这时引擎会继续把提示词处理完并进入 ModelReady——
     * 不记下来的话，下一次 ensureReady 会以为没加载过、又去 loadModel，
     * 撞上状态机的 `check(Initialized)` 直接抛，于是**永远失败**。
     * 首轮批量评估就是这么把 35 条全打成规则兜底的。
     */
    private var inFlightPrompt: String? = null

    val isReady: Boolean get() = loadedKey != null

    val state get() = engine.state

    /** 找设备上的 GGUF：优先应用外部私有目录，再退回内部存储。 */
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
     * 确保模型已加载且系统提示与 [systemPrompt] 一致；不一致就重新加载。
     *
     * @return false = 找不到模型或加载失败（调用方走降级路径，不要抛给用户）
     */
    suspend fun ensureReady(systemPrompt: String): Boolean {
        if (loadedKey == systemPrompt) return true
        return mutex.withLock {
            if (loadedKey == systemPrompt) return@withLock true

            // 上次只是调用方先超时、JNI 后台其实跑完了 → 直接接上
            if (inFlightPrompt == systemPrompt &&
                engine.state.value is com.arm.aichat.InferenceEngine.State.ModelReady
            ) {
                loadedKey = systemPrompt
                return@withLock true
            }

            val file = findModel() ?: return@withLock false
            try {
                // 引擎是进程级单例，可能停在 Error/ModelReady 上；能复位就复位，
                // 复位不了（比如 JNI 还在跑）就让它失败，下次靠上面那条接上。
                if (engine.state.value !is com.arm.aichat.InferenceEngine.State.Initialized) {
                    runCatching { llm.free() }
                }
                inFlightPrompt = systemPrompt
                llm.init(file.absolutePath, systemPrompt)
                loadedKey = systemPrompt
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

    fun unload() {
        runCatching { llm.free() }
        loadedKey = null
        inFlightPrompt = null
    }

    companion object {
        private const val TAG = "SparkSession"

        const val DEFAULT_MAX_TOKENS = 192

        /**
         * 系统提示 = 解析契约。**只输出 JSON**，不解释、不用围栏；
         * 分类名从调用方的科目表传进来——「分类是数据不是代码」。
         */
        fun systemPrompt(expense: List<String>, income: List<String>): String = buildString {
            append("你是运行在用户设备本地的记账解析器，只输出 JSON，不输出任何其他文字。\n")
            append("输入是一句中文口语，可能含错别字、繁体、方言、中英混杂、金额在前、多笔合并。\n")
            append("输出一个 JSON 数组；每个元素为对象，字段：\n")
            append("{\"kind\":\"expense\"或\"income\",\"amount\":数字(单位元,最多两位小数),")
            append("\"category\":\"分类名\",\"note\":\"不超过10字的备注\",\"payee\":\"商户,没有则空串\",")
            append("\"confidence\":0到1}\n")
            append("硬规则：\n")
            append("1. 只有真实消费/收入才记账；闲聊、查询统计、转账、退款一律输出 []。\n")
            append("2. category 只能从这里选，不得自造：\n")
            append("   支出：").append(expense.joinToString("、")).append("\n")
            append("   收入：").append(income.joinToString("、")).append("\n")
            append("3. 多笔合并拆成多个元素；中文数字转阿拉伯（五十块→50）；单位统一元。\n")
            append("4. 例：输入「打车28」输出 [{\"kind\":\"expense\",\"amount\":28,\"category\":\"交通\",")
            append("\"note\":\"打车\",\"payee\":\"\",\"confidence\":0.9}]\n")
            append("5. 例：输入「你好」输出 []\n")
        }
    }
}
