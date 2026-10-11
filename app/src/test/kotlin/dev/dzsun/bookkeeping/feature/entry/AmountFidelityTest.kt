package dev.dzsun.bookkeeping.feature.entry

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金额保真。真机实测的两个 bug 是这里的验收依据：
 *  - 「午餐20元」被模型报成 30 → 必须拦下；
 *  - 「哈啰单车3.5元」→ 模型要是报 3.5 就必须放行。
 *
 * 断言的重点不在「能不能认出数字」，而在**两边都不误伤**：
 * 放行了错数字等于账记错，误判了正确结果等于账记不上，两个都是真损失。
 */
class AmountFidelityTest {

    private fun expected(raw: String, amount: String?): List<BigDecimal> =
        (AmountFidelity.check(raw, amount) as AmountFidelity.Result.Mismatch).expected

    // —— 真机实测的两个 bug ——

    @Test
    fun `午餐20元报成30元必须拦下`() {
        assertFalse(AmountFidelity.isFaithful("午餐20元", "30"))
        assertEquals(listOf(BigDecimal("20")), expected("午餐20元", "30"))
    }

    @Test
    fun `哈啰单车3_5元报3_5放行`() {
        assertTrue(AmountFidelity.isFaithful("哈啰单车3.5元", "3.5"))
    }

    @Test
    fun `哈啰单车3_5元报35必须拦下`() {
        assertFalse(AmountFidelity.isFaithful("哈啰单车3.5元", "35"))
    }

    // —— 放行 ——

    @Test
    fun `没有单位的数字照样比对`() {
        assertTrue(AmountFidelity.isFaithful("买咖啡用了18", "18"))
        assertFalse(AmountFidelity.isFaithful("买咖啡用了18", "28"))
    }

    @Test
    fun `千分位与小数写得不一样也是同一个金额`() {
        assertTrue(AmountFidelity.isFaithful("房租1,200.50元", "1200.5"))
        assertTrue(AmountFidelity.isFaithful("房租1200.50元", "1200.50"))
    }

    @Test
    fun `货币符号也算单位`() {
        assertFalse(AmountFidelity.isFaithful("¥38 的午饭", "44"))
        assertTrue(AmountFidelity.isFaithful("¥38 的午饭", "38"))
    }

    @Test
    fun `中文金额`() {
        assertTrue(AmountFidelity.isFaithful("午饭五十块", "50"))
        assertFalse(AmountFidelity.isFaithful("午饭五十块", "60"))
        assertTrue(AmountFidelity.isFaithful("打车三块五", "3.5"))
        assertFalse(AmountFidelity.isFaithful("打车三块五", "35"))
        assertTrue(AmountFidelity.isFaithful("白菜两块五毛", "2.5"))
    }

    // —— 强集合优先：非金额的数字不许当答案 ——

    @Test
    fun `有带单位的数字时，不带单位的数字不算答案`() {
        // 「记了3笔，一共50元」里的 3 不是金额
        assertFalse(AmountFidelity.isFaithful("昨天记了3笔，一共50元", "3"))
        assertTrue(AmountFidelity.isFaithful("昨天记了3笔，一共50元", "50"))
    }

    @Test
    fun `一句话里有两笔时两个都放行`() {
        assertTrue(AmountFidelity.isFaithful("午饭20元，打车30元", "20"))
        assertTrue(AmountFidelity.isFaithful("午饭20元，打车30元", "30"))
        assertFalse(AmountFidelity.isFaithful("午饭20元，打车30元", "25"))
    }

    // —— 不校验的情形 ——

    @Test
    fun `原文没有数字就不校验`() {
        assertEquals(AmountFidelity.Result.NothingToCheck, AmountFidelity.check("午饭吃了拉面", "38"))
        assertTrue(AmountFidelity.isFaithful("午饭吃了拉面", "38"))
    }

    @Test
    fun `百千万这类省略写法整体不认，宁可不校验也不误判`() {
        // 「一千二」是 1200 不是 1002，「一百二」是 120——本地算错会把对的判成错的
        assertEquals(AmountFidelity.Result.NothingToCheck, AmountFidelity.check("房租一千二", "1200"))
        assertEquals(AmountFidelity.Result.NothingToCheck, AmountFidelity.check("一共一百二十块", "120"))
    }

    @Test
    fun `原文里的负号是方向不是金额，比较的是量`() {
        // 「退款 -38.5」里那个负号表达的是**方向**（退回来的钱），不是金额本身：
        // 这个项目的金额一律是非负的量，方向由 kind（expense/income）承担。
        // 所以比对的是 38.5 这个量，-38.5 也照样放行（它只是同一个量的另一种写法）。
        assertTrue(AmountFidelity.isFaithful("退款 -38.5", "38.5"))
        assertTrue(AmountFidelity.isFaithful("退款 -38.5", "-38.5"))
        assertFalse(AmountFidelity.isFaithful("退款 -38.5", "44"))
        assertEquals(listOf(BigDecimal("38.5")), AmountFidelity.numbersIn("退款 -38.5"))
    }

    @Test
    fun `百分号里的数字也是数字`() {
        assertFalse(AmountFidelity.isFaithful("涨了50%", "38"))
        assertTrue(AmountFidelity.isFaithful("涨了50%", "50"))
    }

    @Test
    fun `解析不出金额时算对不上`() {
        assertTrue(AmountFidelity.check("午饭20元", null) is AmountFidelity.Result.Mismatch)
        assertTrue(AmountFidelity.check("午饭20元", "  ") is AmountFidelity.Result.Mismatch)
        assertTrue(AmountFidelity.check("午饭20元", "二十") is AmountFidelity.Result.Mismatch)
    }

    // —— 提取本身 ——

    @Test
    fun `强集合只收带单位或符号的`() {
        assertEquals(listOf(BigDecimal("3.5")), AmountFidelity.moneyIn("哈啰单车3.5元"))
        assertEquals(emptyList<BigDecimal>(), AmountFidelity.moneyIn("买咖啡用了18"))
        assertEquals(listOf(BigDecimal("38")), AmountFidelity.moneyIn("¥38 的午饭"))
    }

    @Test
    fun `弱集合收全部数字`() {
        assertEquals(
            listOf(BigDecimal("3"), BigDecimal("50")),
            AmountFidelity.numbersIn("昨天记了3笔，一共50元"),
        )
    }
}
