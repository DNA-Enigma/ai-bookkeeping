package dev.dzsun.bookkeeping.feature.report

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 分类月度预算的存取口。
 *
 * **这里是留给数据层的对接点。** 预算终究该和账本存在一起（同一次迁移、同一份备份、
 * 同一个事务），而账本是数据层的。现在那边还没有 budget 表，所以先用
 * [PrefsBudgetStore] 落地在本地 prefs——够把界面跑通、够让用户真的设得上预算。
 *
 * 等 `LedgerRepository` 给出预算查询与写入，在 `ReportModule` 里换绑定即可：
 * 报表页、预算页、记账后的提醒都只认这个接口，一行都不用改。
 */
interface BudgetStore {

    /** 全部已设预算。按分类 id 排序，界面的顺序才是稳定的。 */
    fun observeBudgets(): Flow<List<CategoryBudget>>

    /** 写入某分类的预算。**金额非正表示清除**这条预算——一个入口就够，不必开两个。 */
    suspend fun setBudget(categoryId: String, amountMinor: Long)
}

/**
 * 预算的落盘格式。
 *
 * 用 JSON 而不是自己拼分隔符：分类 id 现在是 `expense.food` 这种固定形状，
 * 但「分类是数据不是代码」（AGENTS.md 约定 3），用户自建的分类 id 什么形状都可能有，
 * 拼分隔符迟早会遇到一个把自身格式撑破的 id，而那是**静默丢预算**。
 * 单测直接盖住编解码往返。
 */
internal object BudgetCodec {

    @Serializable
    internal data class StoredBudget(val categoryId: String, val amountMinor: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredBudget.serializer())

    fun encode(budgets: List<CategoryBudget>): String =
        json.encodeToString(serializer, budgets.map { StoredBudget(it.categoryId, it.amountMinor) })

    /**
     * 解析失败按空处理。这是**有意的降级**：prefs 被外部写坏时，
     * 让每个读预算的页面都崩掉，比显示"还没设预算"更糟。
     * 写的一方只有我们自己，正常路径下不会产生解析不了的内容。
     */
    fun decode(raw: String?): List<CategoryBudget> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
            ?: return emptyList()
        return parsed
            // 非正金额与空 id 是坏数据，不进内存——留着会让界面显示一条点不动的预算
            .filter { it.categoryId.isNotBlank() && it.amountMinor > 0L }
            // 同一分类写了多条时以**最后一条**为准：读的时候不该出现两条互相打架的预算
            .associateBy { it.categoryId }
            .values
            .map { CategoryBudget(it.categoryId, it.amountMinor) }
            .sortedBy { it.categoryId }
    }
}

/** 数据层的预算存储就绪前的落地实现。 */
@Singleton
class PrefsBudgetStore @Inject constructor(
    @ApplicationContext context: Context,
) : BudgetStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _budgets = MutableStateFlow(BudgetCodec.decode(prefs.getString(KEY_BUDGETS, null)))

    override fun observeBudgets(): Flow<List<CategoryBudget>> = _budgets.asStateFlow()

    override suspend fun setBudget(categoryId: String, amountMinor: Long) {
        if (categoryId.isBlank()) return
        val next = (
            _budgets.value.filterNot { it.categoryId == categoryId } +
                if (amountMinor > 0L) listOf(CategoryBudget(categoryId, amountMinor)) else emptyList()
            ).sortedBy { it.categoryId }
        // 先落盘再发信号：读的人立刻拿到新值，进程被杀也不会退回旧值
        prefs.edit().putString(KEY_BUDGETS, BudgetCodec.encode(next)).apply()
        _budgets.value = next
    }

    private companion object {
        const val PREFS_NAME = "budget_store"
        const val KEY_BUDGETS = "category_budgets"
    }
}

/**
 * 预算提醒阈值的**用户设置**，与 `AutoConfirmSettings` 同一个套路：
 * 出厂默认值在 [BudgetThresholds]，用户调过的值存下来且**立刻生效**——
 * 记账页、报表页读的都是同一个源，不需要重启或重进页面。
 */
interface BudgetSettings {
    val thresholds: StateFlow<BudgetThresholds>
    fun setNearRatio(ratio: Float)
    fun setOverRatio(ratio: Float)
}

@Singleton
class PrefsBudgetSettings @Inject constructor(
    @ApplicationContext context: Context,
) : BudgetSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 读进来的值先过一遍 clamp：prefs 里可能躺着旧版本写下的、或手工改过的值，
    // 两条线交叉的阈值会让判定自相矛盾
    private val _thresholds = MutableStateFlow(
        BudgetGate.clamp(
            BudgetThresholds(
                nearRatio = prefs.getFloat(KEY_NEAR, BudgetGate.DEFAULT_NEAR_RATIO),
                overRatio = prefs.getFloat(KEY_OVER, BudgetGate.DEFAULT_OVER_RATIO),
            ),
        ),
    )

    override val thresholds: StateFlow<BudgetThresholds> = _thresholds.asStateFlow()

    override fun setNearRatio(ratio: Float) {
        val current = _thresholds.value
        update(current.copy(nearRatio = BudgetGate.clampNear(ratio, current.overRatio)))
    }

    override fun setOverRatio(ratio: Float) {
        val current = _thresholds.value
        update(current.copy(overRatio = BudgetGate.clampOver(ratio, current.nearRatio)))
    }

    private fun update(next: BudgetThresholds) {
        prefs.edit()
            .putFloat(KEY_NEAR, next.nearRatio)
            .putFloat(KEY_OVER, next.overRatio)
            .apply()
        _thresholds.value = next
    }

    private companion object {
        const val PREFS_NAME = "budget_settings"
        const val KEY_NEAR = "near_ratio"
        const val KEY_OVER = "over_ratio"
    }
}
