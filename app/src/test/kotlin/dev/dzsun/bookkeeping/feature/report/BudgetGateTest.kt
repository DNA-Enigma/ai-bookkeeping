package dev.dzsun.bookkeeping.feature.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预算档位判定。
 *
 * 这块没有"差不多"的余地：判错一档的后果是**该提醒的没提醒**（用户以为没超），
 * 或者没超的乱标红（提醒很快就不被当回事）。所以边界逐个盖：
 * 刚好 80%、刚好 100%、没设预算、预算为零。
 */
class BudgetGateTest {

    private val thresholds = BudgetThresholds()

    /** 1000.00 元的预算。 */
    private val budget = 100_000L

    @Test
    fun `没设预算不是 OK，是 UNSET`() {
        // 并进 OK 的话，报表会把一堆没设预算的分类显示成"正常"，
        // 用户看不出哪些分类根本还没管起来
        assertEquals(BudgetLevel.UNSET, BudgetGate.level(spentMinor = 999_999L, budgetMinor = null, thresholds))
    }

    @Test
    fun `预算为零或负也算 UNSET`() {
        assertEquals(BudgetLevel.UNSET, BudgetGate.level(1L, 0L, thresholds))
        assertEquals(BudgetLevel.UNSET, BudgetGate.level(1L, -100L, thresholds))
    }

    @Test
    fun `花得少是 OK`() {
        assertEquals(BudgetLevel.OK, BudgetGate.level(spentMinor = 50_000L, budgetMinor = budget, thresholds))
    }

    @Test
    fun `刚好到 80% 算接近——取等号`() {
        // 取等号是有意的：写 `>` 的话用户设的 80% 实际生效的是 80.0001%，
        // 而"刚好花到 80%"恰恰是最该提醒的那一刻
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(80_000L, budget, thresholds))
    }

    @Test
    fun `80% 与 100% 之间是接近`() {
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(99_999L, budget, thresholds))
    }

    @Test
    fun `刚好 100% 就算超了`() {
        // "超预算"的判定线就在 100%：花光不等于没超
        assertEquals(BudgetLevel.OVER, BudgetGate.level(100_000L, budget, thresholds))
    }

    @Test
    fun `超过预算一线也是超`() {
        assertEquals(BudgetLevel.OVER, BudgetGate.level(100_001L, budget, thresholds))
    }

    @Test
    fun `比例不整时判定仍然准确`() {
        // 1100.00 的预算花掉 880.00，正好 80%。用整数定点比，
        // 结果不依赖 Float 尾数长什么样
        val b = 110_000L
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(88_000L, b, thresholds))
        assertEquals(BudgetLevel.OK, BudgetGate.level(87_999L, b, thresholds))
        assertEquals(BudgetLevel.OVER, BudgetGate.level(110_000L, b, thresholds))
    }

    @Test
    fun `大额金额不因精度丢失而漏判`() {
        // 100 万的预算：float 只有 24 位尾数，金额转 Float 会丢有效位，
        // 89 万 / 100 万 会算不出准确的 89%
        val bigBudget = 100_000_000L
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(80_000_000L, bigBudget, thresholds))
        assertEquals(BudgetLevel.OVER, BudgetGate.level(100_000_000L, bigBudget, thresholds))
        assertEquals(BudgetLevel.OK, BudgetGate.level(79_999_999L, bigBudget, thresholds))
    }

    @Test
    fun `阈值可调，调完之后按新线判`() {
        // 把「接近」线压到 50%：花掉一半就该提醒了
        val eager = BudgetThresholds(nearRatio = 0.5f, overRatio = 1.0f)
        assertEquals(BudgetLevel.OK, BudgetGate.level(49_000L, budget, eager))
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(50_000L, budget, eager))

        // 把「超了」线抬到 120%：心里留 20% 的缓冲，到 120% 才算真超
        val buffered = BudgetThresholds(nearRatio = 0.8f, overRatio = 1.2f)
        assertEquals(BudgetLevel.NEAR, BudgetGate.level(119_999L, budget, buffered))
        assertEquals(BudgetLevel.OVER, BudgetGate.level(120_000L, budget, buffered))
    }

    @Test
    fun `超出金额只在真超时才为正`() {
        assertEquals(0L, BudgetGate.overspendMinor(50_000L, budget))
        assertEquals(0L, BudgetGate.overspendMinor(100_000L, budget))
        assertEquals(12_000L, BudgetGate.overspendMinor(112_000L, budget))
        // 没设预算时"超了多少"无从谈起，不能返回花掉的全额
        assertEquals(0L, BudgetGate.overspendMinor(112_000L, null))
    }

    @Test
    fun `占用比例没设预算时为 null`() {
        assertNull(BudgetGate.usageRatio(1L, null))
        assertNull(BudgetGate.usageRatio(1L, 0L))
        assertEquals(0.5f, BudgetGate.usageRatio(50_000L, budget)!!)
        // 超支时比例大于 1，不能被夹到 1——夹了就看不出超了多少
        assertEquals(1.5f, BudgetGate.usageRatio(150_000L, budget)!!)
    }

    @Test
    fun `超预算线不许压到 100% 以下`() {
        // 否则"超支"这个提示会写成"已超预算 ¥0.00"，一个自己解释不通的句子。
        // 想更早报警，调的是「接近」那条线
        val crossed = BudgetGate.clamp(BudgetThresholds(nearRatio = 0.9f, overRatio = 0.7f))
        assertEquals(1.0f, crossed.overRatio)
        assertEquals(0.9f, crossed.nearRatio)
    }

    @Test
    fun `两条线始终留出间隔`() {
        // 「接近」线封顶 95%，「超了」线封底 100%：两条线不可能贴到一起，
        // 同一笔就不会既算接近又算超了
        val pushed = BudgetGate.clamp(BudgetThresholds(nearRatio = 0.99f, overRatio = 1.0f))
        assertEquals(BudgetGate.MAX_NEAR_RATIO, pushed.nearRatio)
        assertEquals(1.0f, pushed.overRatio)
    }

    @Test
    fun `阈值被收进合法范围`() {
        val tooLow = BudgetGate.clamp(BudgetThresholds(nearRatio = 0.1f, overRatio = 0.2f))
        assertEquals(BudgetGate.MIN_NEAR_RATIO, tooLow.nearRatio)
        assertEquals(BudgetGate.MIN_OVER_RATIO, tooLow.overRatio)

        val tooHigh = BudgetGate.clamp(BudgetThresholds(nearRatio = 1.5f, overRatio = 9f))
        assertEquals(BudgetGate.MAX_NEAR_RATIO, tooHigh.nearRatio)
        assertEquals(BudgetGate.MAX_OVER_RATIO, tooHigh.overRatio)
    }

    @Test
    fun `单条线按范围夹住`() {
        // 「接近」线封顶 95%，且不越过「超了」线
        assertEquals(0.9f, BudgetGate.clampNear(0.9f, overRatio = 1.0f))
        assertEquals(BudgetGate.MAX_NEAR_RATIO, BudgetGate.clampNear(0.99f, overRatio = 1.0f))
        assertEquals(BudgetGate.MIN_NEAR_RATIO, BudgetGate.clampNear(0.1f, overRatio = 1.0f))
        // 「超了」线封底 100%：低于它的输入一律抬上来
        assertEquals(1.0f, BudgetGate.clampOver(0.5f, nearRatio = 0.8f))
        assertEquals(BudgetGate.MAX_OVER_RATIO, BudgetGate.clampOver(9f, nearRatio = 0.8f))
    }

    @Test
    fun `默认阈值是产品定的 80 与 100`() {
        assertEquals(0.80f, BudgetThresholds().nearRatio)
        assertEquals(1.00f, BudgetThresholds().overRatio)
    }
}
