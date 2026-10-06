package dev.dzsun.bookkeeping.core.ledger

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `assets/entry_rules.json` 的形状。多出来的键一律忽略，加参数不必改这里。 */
@Serializable
internal data class EntryRulesFile(
    val version: Int = 1,
    val confidence: ConfidenceRules = ConfidenceRules(),
)

@Serializable
internal data class ConfidenceRules(
    @SerialName("auto_post_threshold") val autoPostThreshold: Float = ConfidenceGate.DEFAULT_THRESHOLD,
    val min: Float = ConfidenceGate.MIN_THRESHOLD,
    val max: Float = ConfidenceGate.MAX_THRESHOLD,
)

/**
 * 读记账判定的可调参数。
 *
 * 和 `StatementFormatCatalog` 同一个套路：**读失败返回兜底值而不是抛**——
 * 一个配置文件的格式错误不该让整个记账流程起不来，而 0.85 是个安全的兜底
 * （偏保守：宁可多问一句）。
 *
 * 注意这里只管**出厂默认值**。用户在设置里调过之后存哪儿是界面的事
 * （`PrefsAutoConfirmSettings`），这里的值只作为「没调过时用哪个」的来源。
 */
@Singleton
class EntryRuleCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: ConfidenceRules? = null

    /** 自动入账的置信度阈值，已夹进合法范围。 */
    fun autoPostThreshold(): Float = ConfidenceGate.clamp(rules().autoPostThreshold)

    /**
     * 阈值的合法范围。
     *
     * 从配置里一起读，是为了让「默认值」和「可调范围」出自同一份文件——
     * 分两处写的话，改了一个忘了另一个就会出现「默认值落在自己声明的范围之外」。
     */
    fun thresholdRange(): ClosedFloatingPointRange<Float> {
        val rules = rules()
        val min = rules.min.coerceAtMost(rules.max)
        return min..rules.max
    }

    private fun rules(): ConfidenceRules {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    private fun load(): ConfidenceRules = runCatching {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        json.decodeFromString<EntryRulesFile>(text).confidence
    }.getOrDefault(ConfidenceRules())

    companion object {
        const val ASSET_NAME = "entry_rules.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
