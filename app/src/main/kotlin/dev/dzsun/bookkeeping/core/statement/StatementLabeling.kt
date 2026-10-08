package dev.dzsun.bookkeeping.core.statement

/**
 * 账单**自己没说清「这是干什么的」**时，拿什么当这行的名字。
 *
 * 支付宝实测 538 笔支出里有 12 笔（2%）两列都没信息：
 *
 * ```
 * 交易分类=餐饮美食 | 交易对方=飞 | 商品说明=收钱码收款 | 9.50
 * ```
 *
 * 「飞」是个人收钱码被截断的对方名，「收钱码收款」说的是**怎么付的钱**，
 * 不是买了什么。于是这一行在预览里显示成「收钱码收款」，用户看不出是干什么的。
 *
 * 退回**账单自带的「交易分类」列**（餐饮美食）是此时唯一有信息量的东西——
 * 它仍然是账单给的事实，不是我们猜的。
 *
 * 这不是「AI 归类」：本层只读账单里已有的字段，一个字都不推测。
 */
object StatementLabeling {

    /**
     * 商户名短于或等于这个长度就当它**没有信息量**。
     *
     * 真机实测：支付宝把个人收钱码的对方名截断成 1–2 字（596 笔里 27 笔 ≤2 字，
     * 如「飞」「菜店」「烤肠」），拿它做主标题只会把有用的东西挤掉。
     */
    const val UNINFORMATIVE_MERCHANT_MAX_LENGTH = 2

    /**
     * 平台话术：描述列里写这些词时，它回答的是「怎么付的钱」，不是「买了什么」。
     *
     * 词表取自真实账单里出现过的取值（`经营码交易` 65 笔、`收钱码收款` 56 笔、
     * `移动支付` 15 笔、`订单付款` 9 笔、`支付宝支付0273` 7 笔、`转账` 4 笔）。
     * 允许词尾带数字/空格/下划线——平台会把批次号缀在后面。
     *
     * **必须整串命中**：`长安通（互联互通） 充值`、`在 888888 消费扫码付款`
     * 这类前缀带信息的描述不能因为里面有「充值」「付款」就被吞掉。
     */
    private val PLATFORM_BOILERPLATE = Regex(
        "^(?:" +
            "收钱码收款|经营码交易|移动支付|订单付款|支付宝支付\\d*|扫码支付|" +
            "付款|充值|转账|还款|提现|红包|AA收款|交易付款|消费付款" +
            ")[\\d\\s_]*$",
    )

    /**
     * 这个商户名有没有信息量。
     *
     * 三种算没有：一个字都没有、被账单用 `/` 占了位、长度 ≤ [UNINFORMATIVE_MERCHANT_MAX_LENGTH]、
     * 以及脱敏后的账号这种**一个字母都没有**的串（如 `173******22`）。
     */
    fun isUninformativeMerchant(merchant: String?): Boolean {
        val name = merchant?.trim().orEmpty()
        if (name.isEmpty()) return true
        // 账单用「/」表示这一格没有值，不是真的有个叫「/」的商户
        if (name.all { it == '/' }) return true
        if (name.codePointCount(0, name.length) <= UNINFORMATIVE_MERCHANT_MAX_LENGTH) return true
        return name.none { it.isLetter() }
    }

    /**
     * 这句描述是不是平台话术，或者干脆是空的。
     *
     * 空描述和话术一样没有信息量——而它比话术更容易被漏掉：老规则里
     * 「商户被截断 + 描述为空」会退到「第 N 行」，那是三档里最没用的一档。
     */
    fun isUninformativeDescription(description: String?): Boolean {
        val text = description?.trim().orEmpty()
        if (text.isEmpty()) return true
        return PLATFORM_BOILERPLATE.matches(text)
    }

    /**
     * 这行只能拿「交易分类」当名字时，返回它；不适用时返回 null。
     *
     * 两个条件都要满足：商户没有信息量**且**描述没有信息量。
     * 只看其中一条会误伤——真实账单里有 140 笔是「商户名有信息、描述是话术」
     * （`小东北麻辣烫` + `收钱码收款`），那些必须照旧显示商户。
     *
     * [rawType] 为空时返回 null：账单自己没给分类，就没有更好的名字可用，
     * 宁可让调用方退回原来的规则，也不要编一个。
     */
    fun statementTypeName(merchant: String?, description: String?, rawType: String?): String? {
        if (!isUninformativeMerchant(merchant)) return null
        if (!isUninformativeDescription(description)) return null
        return rawType?.trim()?.takeIf { it.isNotEmpty() }
    }
}
