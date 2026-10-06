package dev.dzsun.bookkeeping.feature.report

import kotlin.math.roundToLong

/**
 * 某个分类的月度预算。
 *
 * 金额一律**整数最小单位**，与账本其余部分同一个口径——预算也是钱，
 * 用浮点存的话「餐饮 1100 元」这个数本身就会先失真。
 */
data class CategoryBudget(
    val categoryId: String,
    val amountMinor: Long,
)

/**
 * 预算使用档位。
 *
 * [UNSET] 单独一档，**不并进 [OK]**：「没设预算」和「花得少」是两回事。
 * 混在一起的话报表会显示一片"正常"，用户看不出哪些分类根本还没管起来。
 */
enum class BudgetLevel {
    UNSET,
    OK,
    NEAR,
    OVER,
}

/**
 * 提醒阈值。默认 80% 算接近、100% 算超。
 *
 * 与 [BudgetGate] 里的兜底值同源：阈值的真源是这里的数据类，
 * 用户调过之后存进 [BudgetSettings]，读不到就用默认值。
 */
data class BudgetThresholds(
    val nearRatio: Float = BudgetGate.DEFAULT_NEAR_RATIO,
    val overRatio: Float = BudgetGate.DEFAULT_OVER_RATIO,
)

/**
 * 「这笔花到哪一档了」。
 *
 * 纯函数、无 Android 依赖，所以边界（刚好 80%、刚好 100%、没设预算）都能直接单测。
 *
 * **为什么这一层不放在 `core` 下**：预算目前是界面侧的能力（数据层的报表/预算查询
 * 还在做），阈值也只是提醒线的口径，不参与记账。等预算落进账本表结构、
 * 变成需要跨端一致的规则时，这段应当整体挪到数据层——判定放哪边，
 * 取决于谁拥有那份数据，不取决于它简单不简单。
 */
object BudgetGate {

    const val DEFAULT_NEAR_RATIO = 0.80f
    const val DEFAULT_OVER_RATIO = 1.00f

    /** 50%：再低就等于每笔都提醒，那提醒就不被当回事了。 */
    const val MIN_NEAR_RATIO = 0.50f

    /** 95%：留一点余地，否则「接近」直接贴着「已超」，两条线失去区分度。 */
    const val MAX_NEAR_RATIO = 0.95f

    /**
     * 超预算的线**只允许往上调**（给自己留缓冲，比如到 120% 才算超）。
     *
     * 不允许低于 100%：「花掉 90% 就叫超支」的话，提示会写成「已超预算 ¥0.00」——
     * 一个自己解释不通的句子。要更早报警，拧的是 [nearRatio] 那条线。
     */
    const val MIN_OVER_RATIO = 1.00f
    const val MAX_OVER_RATIO = 2.00f

    /** 两条线至少隔 5 个点，否则同一笔会同时算「接近」和「超了」，提醒没有意义。 */
    private const val MIN_GAP = 0.05f

    /**
     * 比例的定点基数。阈值与金额都在整数上比，**不先把金额转成 Float 再除**。
     *
     * `0.8f` 不是精确的 0.8，大额金额转 Float 时还会再丢几位有效数字，
     * 于是"刚好花到 80%"到底算不算到，会取决于两个数的二进制尾数——
     * **同一笔账换个金额就表现不一致，是最难查的一类 bug**。
     * 阈值只是提醒线，但少一次提醒就是静默失效。整数比较没有这个问题，
     * 代价只有一次乘法（`spentMinor` 到 1e12 也不会溢出）。
     */
    private const val RATIO_BASIS = 10_000L

    /** 这笔花到哪一档。预算为空或非正一律 [BudgetLevel.UNSET]。 */
    fun level(spentMinor: Long, budgetMinor: Long?, thresholds: BudgetThresholds): BudgetLevel {
        if (budgetMinor == null || budgetMinor <= 0L) return BudgetLevel.UNSET
        val near = basisOf(clampNear(thresholds.nearRatio, thresholds.overRatio))
        val over = basisOf(clampOver(thresholds.overRatio, thresholds.nearRatio))
        return when {
            spentMinor * RATIO_BASIS >= over * budgetMinor -> BudgetLevel.OVER
            spentMinor * RATIO_BASIS >= near * budgetMinor -> BudgetLevel.NEAR
            else -> BudgetLevel.OK
        }
    }

    /** 超出多少钱（正数＝超支）。没超、没设预算都是 0。 */
    fun overspendMinor(spentMinor: Long, budgetMinor: Long?): Long =
        if (budgetMinor == null) 0L else (spentMinor - budgetMinor).coerceAtLeast(0L)

    /**
     * 预算占用比例，给进度条用。**只用于显示**，判定一律走 [level]——
     * 两者要是各算各的，会出现「进度条满了但没标红」这种自相矛盾的画面。
     */
    fun usageRatio(spentMinor: Long, budgetMinor: Long?): Float? =
        if (budgetMinor == null || budgetMinor <= 0L) null else spentMinor.toFloat() / budgetMinor

    /** 把用户设的值收进合法范围，并保证两条线不交叉。 */
    fun clamp(thresholds: BudgetThresholds): BudgetThresholds {
        val over = thresholds.overRatio.coerceIn(MIN_OVER_RATIO, MAX_OVER_RATIO)
        val near = thresholds.nearRatio.coerceIn(
            MIN_NEAR_RATIO,
            minOf(MAX_NEAR_RATIO, over - MIN_GAP),
        )
        return BudgetThresholds(nearRatio = near, overRatio = over)
    }

    fun clampNear(ratio: Float, overRatio: Float = DEFAULT_OVER_RATIO): Float {
        val ceiling = minOf(MAX_NEAR_RATIO, overRatio.coerceIn(MIN_OVER_RATIO, MAX_OVER_RATIO) - MIN_GAP)
        return ratio.coerceIn(MIN_NEAR_RATIO, maxOf(MIN_NEAR_RATIO, ceiling))
    }

    fun clampOver(ratio: Float, nearRatio: Float = DEFAULT_NEAR_RATIO): Float {
        val floor = maxOf(MIN_OVER_RATIO, nearRatio.coerceIn(MIN_NEAR_RATIO, MAX_NEAR_RATIO) + MIN_GAP)
        return ratio.coerceIn(minOf(floor, MAX_OVER_RATIO), MAX_OVER_RATIO)
    }

    private fun basisOf(ratio: Float): Long = (ratio * RATIO_BASIS).roundToLong()
}
