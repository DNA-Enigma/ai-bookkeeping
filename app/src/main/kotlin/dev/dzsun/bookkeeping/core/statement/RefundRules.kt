package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.money.Money

/**
 * 一笔退款到底退回来多少钱。
 *
 * 这是冲减必须回答的第一个问题，而**流水里往往没有专门的金额列**——
 * 微信把退款写在原消费那一行的状态里（实测取值：「已全额退款」「已退款¥6.60」）。
 * 所以只能从状态文本里认。
 *
 * 认不出来时一律返回 null，**绝不猜成"全额"**：猜大了会让这个月凭空少一笔支出，
 * 猜小了会让用户以为退款没到账，两种都是静默的错账。
 * 认不出的行照旧展示给用户（「退款金额无法从流水确定」），但不提供入库入口。
 */
object RefundAmount {

    /**
     * @param status 状态列原文，可为 null。
     * @param format 该账单的格式定义，退款标记从它的配置里读。
     * @param chargeAmount 原消费金额（正数）。退回来的钱不可能超过它。
     * @return 退回来的金额；认不出来或明显不合理时为 null。
     */
    fun resolve(status: String?, format: StatementFormat, chargeAmount: Money): Money? {
        val text = status?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 先认「全额」再认金额：`已全额退款` 这类文本里也可能带数字（如「已全额退款¥20.00」），
        // 两者结论一致，但「全额」是平台自己下的判断，比我们解析出来的数字更权威。
        if (format.fullRefundMarkers.any { it.isNotBlank() && text.contains(it) }) {
            return chargeAmount
        }

        val amount = parseAmountInText(text, chargeAmount.currency) ?: return null
        if (amount.amountMinor <= 0 || amount > chargeAmount) return null
        return amount
    }

    /** 退回来的钱是不是少于原消费。金额未知时返回 null——「不知道」不是「部分」。 */
    fun isPartial(refunded: Money?, chargeAmount: Money): Boolean? =
        refunded?.let { it < chargeAmount }

    /**
     * 从文本里认一个金额。两种最常见写法：`¥6.60`（符号在前）、`6.60元`（单位在后）。
     *
     * 带符号/单位的才认，裸数字不认——状态文本里的裸数字可能是单号片段或期数，
     * 认错了就是一个凭空冒出来的金额。
     */
    private fun parseAmountInText(text: String, currency: String): Money? {
        val match = PREFIXED.find(text) ?: SUFFIXED.find(text) ?: return null
        val number = match.groupValues[1]
        return runCatching { Money.parse(number, currency) }.getOrNull()
    }

    private val PREFIXED = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]+)?)""")
    private val SUFFIXED = Regex("""([0-9]+(?:\.[0-9]+)?)\s*元""")
}
