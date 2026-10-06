package dev.dzsun.bookkeeping.core.money

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun `十进制小数相加不产生浮点误差`() {
        // 这正是浮点记账会出错的地方：0.1 + 0.2 == 0.30000000000000004
        val sum = Money.parse("0.10", "CNY") + Money.parse("0.20", "CNY")
        assertEquals(Money.parse("0.30", "CNY"), sum)
        assertEquals(30L, sum.amountMinor)
    }

    @Test
    fun `连续累加一千次一分钱恰好是一十元`() {
        var acc = Money.zero("CNY")
        repeat(1000) { acc += Money.parse("0.01", "CNY") }
        assertEquals(Money.parse("10.00", "CNY"), acc)
    }

    @Test
    fun `解析与格式化互为逆运算`() {
        listOf("38.00", "0.01", "1234567.89").forEach { text ->
            assertEquals(text, Money.parse(text, "CNY").toPlainString())
        }
    }

    @Test
    fun `小数位随币种变化而不是写死两位`() {
        assertEquals(2, Money.parse("1", "CNY").fractionDigits)
        // 日元没有小数单位
        assertEquals(0, Money.parse("100", "JPY").fractionDigits)
        assertEquals(100L, Money.parse("100", "JPY").amountMinor)
        // 科威特第纳尔是三位
        assertEquals(3, Money.parse("1", "KWD").fractionDigits)
        assertEquals(1000L, Money.parse("1", "KWD").amountMinor)
    }

    @Test
    fun `超出币种精度时银行家舍入`() {
        assertEquals(3800L, Money.parse("38.005", "CNY").amountMinor)
        assertEquals(3802L, Money.parse("38.015", "CNY").amountMinor)
    }

    @Test
    fun `拆分各份之和恒等于原值`() {
        val total = Money.parse("100.00", "CNY")
        listOf(
            listOf(1, 1, 1),
            listOf(1, 2, 3),
            listOf(1, 1, 1, 1, 1, 1, 1),
            listOf(7, 11, 13),
        ).forEach { ratios ->
            val parts = total.allocate(ratios)
            assertEquals("比例 $ratios 拆分后总额变了", total, parts.fold(Money.zero("CNY"), Money::plus))
        }
    }

    @Test
    fun `拆分不会把份额分给比例为零的一方`() {
        val parts = Money.parse("0.10", "CNY").allocate(listOf(3, 0))
        assertEquals(Money.parse("0.10", "CNY"), parts[0])
        assertEquals(Money.zero("CNY"), parts[1])
    }

    @Test
    fun `负数拆分同样守恒`() {
        val total = Money.parse("-100.00", "CNY")
        val parts = total.allocate(listOf(1, 2, 3))
        assertEquals(total, parts.fold(Money.zero("CNY"), Money::plus))
    }

    @Test
    fun `乘法按银行家舍入`() {
        // 1050 * 0.5 = 525，整数
        assertEquals(525L, Money.of(1050, "CNY").times(BigDecimal("0.5")).amountMinor)
        // 奇数分的一半：1051 * 0.5 = 525.5 → 舍入到偶数 526
        assertEquals(526L, Money.of(1051, "CNY").times(BigDecimal("0.5")).amountMinor)
        // 1053 * 0.5 = 526.5 → 舍入到偶数 526
        assertEquals(526L, Money.of(1053, "CNY").times(BigDecimal("0.5")).amountMinor)
    }

    @Test
    fun `币种不一致时拒绝运算`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money.parse("1.00", "CNY") + Money.parse("1.00", "USD")
        }
    }

    @Test
    fun `溢出时抛错而不是静默回绕`() {
        assertThrows(ArithmeticException::class.java) {
            Money.of(Long.MAX_VALUE, "CNY") + Money.of(1, "CNY")
        }
    }

    @Test
    fun `格式化带货币符号`() {
        val text = Money.parse("1234.50", "CNY").format(Locale.CHINA)
        assertTrue("期望含 1234.50，实际为 $text", text.contains("1,234.50"))
        assertTrue("期望含货币符号，实际为 $text", text.contains("¥"))
    }

    @Test
    fun `未知币种降级为代码加数字而不是崩溃`() {
        val money = Money.of(12345, "NOPE")
        assertEquals("NOPE 123.45", money.format(Locale.CHINA))
    }
}
