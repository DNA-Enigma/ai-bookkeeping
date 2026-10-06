package dev.dzsun.bookkeeping.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * 一笔金额。
 *
 * 内部一律以**最小货币单位的整数**表示（人民币的"分"、日元的"円"），
 * 因为浮点记账必错：`0.1 + 0.2 != 0.3` 在账本里是数据损坏，不是精度损失。
 * 只有在解析文本、格式化输出、以及乘除这些边界上才短暂经过 [BigDecimal]。
 *
 * 小数位数从 [Currency] 查得，不写死成 2——日元是 0 位，第纳尔是 3 位。
 */
data class Money private constructor(
    val minor: Long,
    val currency: String,
) : Comparable<Money> {

    val fractionDigits: Int get() = fractionDigitsOf(currency)

    /** 以最小单位表示的数值，如 3800 表示 ¥38.00。 */
    val amountMinor: Long get() = minor

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    operator fun times(factor: Int): Money = times(BigDecimal.valueOf(factor.toLong()))

    /** 按 [factor] 缩放，结果按银行家舍入到最小单位。 */
    fun times(factor: BigDecimal): Money =
        Money(
            BigDecimal.valueOf(minor)
                .multiply(factor)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact(),
            currency,
        )

    /**
     * 按 [ratios] 的比例拆分，**保证各份之和恒等于原值**——不会凭空多出或丢掉一分钱。
     * 余数依次分给比例非零的份额。
     */
    fun allocate(ratios: List<Int>): List<Money> {
        require(ratios.isNotEmpty()) { "拆分比例不能为空" }
        require(ratios.all { it >= 0 }) { "拆分比例不能为负：$ratios" }
        val total = ratios.sum()
        require(total > 0) { "拆分比例之和必须为正：$ratios" }

        val shares = ratios.map { it.toLong() * minor / total }.toMutableList()
        var remainder = minor - shares.sum()

        val eligible = ratios.indices.filter { ratios[it] > 0 }
        val step = if (remainder > 0) 1L else -1L
        var cursor = 0
        while (remainder != 0L) {
            shares[eligible[cursor % eligible.size]] += step
            remainder -= step
            cursor++
        }
        return shares.map { Money(it, currency) }
    }

    fun abs(): Money = if (minor < 0) Money(-minor, currency) else this

    fun isZero(): Boolean = minor == 0L

    fun isNegative(): Boolean = minor < 0L

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minor.compareTo(other.minor)
    }

    /** 不含货币符号的纯数字，如 "38.00"。用于输入框回填与调试。 */
    fun toPlainString(): String =
        BigDecimal.valueOf(minor).movePointLeft(fractionDigits).setScale(fractionDigits).toPlainString()

    /** 带货币符号的展示文本，如 "¥38.00"。符号与分隔符交给 locale 决定。 */
    fun format(locale: Locale = Locale.CHINA): String {
        // ISO 4217 里 "XYZ" / "XXX" 这类测试代码的小数位数是 -1，交给 NumberFormat 会输出怪东西
        val currencyOrNull = currencyOrNull(currency)?.takeIf { it.defaultFractionDigits >= 0 }
            ?: return "$currency ${toPlainString()}"
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = currencyOrNull
        format.minimumFractionDigits = fractionDigits
        format.maximumFractionDigits = fractionDigits
        return format.format(BigDecimal.valueOf(minor).movePointLeft(fractionDigits))
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "币种不一致：$currency 与 ${other.currency}" }
    }

    companion object {
        /** 查不到币种时的退路。绝大多数货币是两位小数。 */
        const val DEFAULT_FRACTION_DIGITS = 2

        fun of(minor: Long, currency: String): Money {
            require(currency.isNotBlank()) { "币种不能为空" }
            return Money(minor, currency.uppercase(Locale.ROOT))
        }

        fun zero(currency: String): Money = of(0, currency)

        /**
         * 从用户输入的十进制文本解析，如 "38.00" → 3800 分。
         * 小数位超出该币种精度时按银行家舍入收到最小单位（"38.005" → 3800 分），
         * 不做截断——截断会让长期累加系统性偏小。
         */
        fun parse(text: String, currency: String): Money {
            val normalized = text.trim().replace(",", "").replace("，", "")
            require(normalized.isNotEmpty()) { "金额不能为空" }
            val decimal = normalized.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("无法解析的金额：$text")
            val digits = fractionDigitsOf(currency)
            return of(
                decimal.movePointRight(digits).setScale(0, RoundingMode.HALF_EVEN).longValueExact(),
                currency,
            )
        }
    }
}

internal fun fractionDigitsOf(currencyCode: String): Int =
    currencyOrNull(currencyCode)?.defaultFractionDigits?.takeIf { it >= 0 }
        ?: Money.DEFAULT_FRACTION_DIGITS

private fun currencyOrNull(currencyCode: String): Currency? =
    runCatching { Currency.getInstance(currencyCode.uppercase(Locale.ROOT)) }.getOrNull()
