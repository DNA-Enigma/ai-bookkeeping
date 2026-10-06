package dev.dzsun.bookkeeping.feature.entry

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dzsun.bookkeeping.core.ledger.ConfidenceGate
import dev.dzsun.bookkeeping.core.ledger.EntryRuleCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动入账阈值的**用户设置**。
 *
 * 分工（见 [ConfidenceGate] 与 [EntryRuleCatalog]）：**出厂默认值与判定规则在数据层**，
 * 界面这一层只负责「用户调过之后存下来、并且立刻生效」。
 * 阈值判断一律走 [ConfidenceGate]——界面绝不自己比大小，
 * 否则两边迟早会出现「确认页说要确认、入账却已经自动记了」。
 */
interface AutoConfirmSettings {
    /** 当前阈值。用户改过就是他的值，没改过就是 assets 里的出厂默认。 */
    val threshold: StateFlow<Float>

    /** 可调范围，与出厂默认值出自同一份配置。 */
    val range: ClosedFloatingPointRange<Float>

    fun setThreshold(value: Float)
}

@Singleton
class PrefsAutoConfirmSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val rules: EntryRuleCatalog,
) : AutoConfirmSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val range: ClosedFloatingPointRange<Float> = rules.thresholdRange()

    private val _threshold = MutableStateFlow(
        ConfidenceGate.clamp(prefs.getFloat(KEY_THRESHOLD, rules.autoPostThreshold())),
    )

    override val threshold: StateFlow<Float> = _threshold.asStateFlow()

    override fun setThreshold(value: Float) {
        val clamped = ConfidenceGate.clamp(value)
        // 先落盘再发信号：读的人立刻拿到新值，进程被杀也不会退回旧值
        prefs.edit().putFloat(KEY_THRESHOLD, clamped).apply()
        _threshold.value = clamped
    }

    private companion object {
        const val PREFS_NAME = "entry_auto_confirm"
        const val KEY_THRESHOLD = "confidence_threshold"
    }
}

/**
 * 撤销一笔刚自动入账的账。
 *
 * ⚠️ **目前没有实现，界面因此不显示撤销按钮。** 原因是硬缺口而不是没做：
 * `LedgerRepository` 只有 `post`，没有作废/删除；直接拿 `journalDao().deleteById`
 * 会绕过凭证与分录的同一事务，留下孤儿分录。而 `core/ledger` 是数据层的地方
 * （分工见 AGENTS.md），界面的手够不着，也不该伸过去。
 *
 * 需要的接口形状（已提给数据层，见 AGENTS.md 回话区）：
 * ```kotlin
 * suspend fun void(journalId: String): Boolean
 * ```
 * 拿到之后在 [AutoConfirmModule] 换绑定即可，界面一行都不用改。
 */
interface AutoEntryUndo {
    /** 有没有撤销能力。**没有就不渲染按钮**——显示一个点不动的按钮比不显示更糟。 */
    val isAvailable: Boolean

    /** 成功返回 true；false 表示没撤销成功，界面要如实告诉用户。 */
    suspend fun undo(journalId: String): Boolean
}

/** 数据层还没提供作废接口时的占位。 */
object UnavailableAutoEntryUndo : AutoEntryUndo {
    override val isAvailable: Boolean = false

    override suspend fun undo(journalId: String): Boolean = false
}

@Module
@InstallIn(SingletonComponent::class)
object AutoConfirmModule {

    @Provides
    @Singleton
    fun provideAutoConfirmSettings(impl: PrefsAutoConfirmSettings): AutoConfirmSettings = impl

    @Provides
    @Singleton
    fun provideAutoEntryUndo(): AutoEntryUndo = UnavailableAutoEntryUndo
}
