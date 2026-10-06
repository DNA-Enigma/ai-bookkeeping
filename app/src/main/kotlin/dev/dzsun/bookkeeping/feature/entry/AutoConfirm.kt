package dev.dzsun.bookkeeping.feature.entry

import android.content.Context
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
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
 * 产品口径是**删除**（不是作废留痕）：撤销窗口只在会话内，用户刚看见就反悔，
 * 那笔不该在账本里留下任何痕迹。
 *
 * [isAvailable] 为 false 时**不渲染按钮**——显示一个点不动的按钮比不显示更糟。
 */
interface AutoEntryUndo {
    /** 有没有撤销能力。 */
    val isAvailable: Boolean

    /** 成功返回 true；false 表示没撤销成功，界面要如实告诉用户。 */
    suspend fun undo(journalId: String): Boolean
}

/**
 * 按凭证 id 删除。**级联**清掉分录与明细（实体上是 `ON DELETE CASCADE`），
 * 三张表的删除在同一个事务里——不存在"凭证没了分录还在"的中间态。
 *
 * 用的是数据层已有的 DAO，不越界改 `core` 下的代码。日后数据层若收拢成
 * `LedgerRepository.delete`，换绑定即可，界面一行都不用改。
 */
@Singleton
class DbAutoEntryUndo @Inject constructor(
    private val database: LedgerDatabase,
) : AutoEntryUndo {

    override val isAvailable: Boolean = true

    override suspend fun undo(journalId: String): Boolean = try {
        database.withTransaction {
            val existing = database.journalDao().findById(journalId)
            if (existing == null) {
                false
            } else {
                // 明细先删再删凭证；分录靠外键级联，和凭证同进退
                database.journalItemDao().deleteByJournal(journalId)
                database.journalDao().deleteById(journalId)
                true
            }
        }
    } catch (_: Exception) {
        false
    }
}

/** 数据层还没提供删除接口时的占位。 */
object UnavailableAutoEntryUndo : AutoEntryUndo {
    override val isAvailable: Boolean = false

    override suspend fun undo(journalId: String): Boolean = false
}

/**
 * 撤销之后提示条与错误文案怎么变。**纯函数**，所以能直接单测：
 * 成功就收掉提示条；失败要留下提示条但收起撤销按钮，并如实报错。
 */
fun AutoSavedNotice.afterUndo(success: Boolean): Pair<AutoSavedNotice?, String?> =
    if (success) {
        null to null
    } else {
        copy(undoAvailable = false) to "撤销没成功，这笔还在账本里"
    }

@Module
@InstallIn(SingletonComponent::class)
object AutoConfirmModule {

    @Provides
    @Singleton
    fun provideAutoConfirmSettings(impl: PrefsAutoConfirmSettings): AutoConfirmSettings = impl

    @Provides
    @Singleton
    fun provideAutoEntryUndo(impl: DbAutoEntryUndo): AutoEntryUndo = impl
}
