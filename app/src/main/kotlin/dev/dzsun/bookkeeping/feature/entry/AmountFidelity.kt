package dev.dzsun.bookkeeping.feature.entry

import java.math.BigDecimal

/**
 * 金额保真 —— 「原文里有什么数字，解析就只能报那些数字」。
 *
 * ## 为什么要有这一层（真机实测的两个 bug）
 *
 * 浮点解析、复式平衡这些**结构**上的错，本地代码能保证不出；但模型报一个
 * **结构完全合法、数字却是错的**金额，之前没有任何东西能拦住：
 *
 *  - 真机输入「午餐20元」→ 模型回「午餐30元」，20 变 30，账记错了还看不出来；
 *  - 真机输入「哈啰单车3.5元」→ 模型答成「哈啰单车是共享单车功能」，根本没记账。
 *
 * 第一条就是这里挡的：**记错金额比记不上更糟**——记不上用户会重来一次，
 * 记错了要等到对账才发现，那时已经不知道哪一笔是错的。
 *
 * ## 判据（两档，刻意不对称）
 *
 * 1. **强集合**：紧跟货币单位或符号的数字（`3.5元`、`¥38`、`五十块`）。
 *    只要文本里存在强集合，解析出的金额就**必须**落在强集合里。
 * 2. **弱集合**：文本里所有数字（`打车28元` 没有单位时也算）。
 *    强集合为空时才拿它当判据。
 *
 * 为什么分两档：「昨天记了3笔，一共50」里那个 3 不是金额。强集合非空时
 * 就只看强集合，避免把「3」当成合法答案放行。
 *
 * ## 一条刻意的不作为：百/千/万不解析
 *
 * 「一千二」是 1200 而不是 1002，「一百二」是 120——这类省略写法本地解析会出错，
 * 而**误判（把对的判成错的）比漏判伤得多**：误判会让一条本来正确的模型结果被丢掉。
 * 所以含 `百千万亿` 的中文数字整体不提取，这条句子就不做校验（[Result.NothingToCheck]）。
 * 已知代价：「打车二十元」这类**阿拉伯数字缺失**的句子挡不住模型报错数。
 *
 * 纯函数、无 Android 依赖，每条边界都能直接单测。
 *
 * ## 比较的是「量」，不是带符号的数
 *
 * 原文里的负号（「退款 -38.5」）表达的是**方向**，不是金额本身——
 * 这个项目的金额一律是非负的量，方向由 `EntryKind` 承担。
 * 所以提取时只取数字部分，`-38.5` 与 `38.5` 是同一个量。
 */
object AmountFidelity {

    sealed interface Result {
        /** 文本里没有可比的数字（或只有认不准的中文数字）→ 不校验，放行。 */
        data object NothingToCheck : Result

        data object Match : Result

        /** 原文有数字，但解析出的金额对不上。 */
        data class Mismatch(val parsed: BigDecimal?, val expected: List<BigDecimal>) : Result
    }

    /** 一句话的判定。方便调用方直接写 `if (!AmountFidelity.isFaithful(...))`。 */
    fun isFaithful(raw: String, parsedAmount: String?): Boolean = check(raw, parsedAmount) !is Result.Mismatch

    /**
     * [Result.Mismatch] 的**给用户看的**解释：模型报了什么、原话里的数是什么。
     *
     * 单独一个函数是为了让记账解析和对话两条路说同一句话 ——
     * 同一个拒因在卡片和气泡上写成两种措辞，用户会以为是两回事。
     */
    fun describe(mismatch: Result.Mismatch): String = describe(mismatch.parsed, mismatch.expected)

    /** 同上，给那些只带了两个字段的调用方（如 `EntryDecode.AmountMismatch`）。 */
    fun describe(parsed: BigDecimal?, expected: List<BigDecimal>): String {
        val got = parsed?.stripTrailingZeros()?.toPlainString() ?: "没给出金额"
        val want = expected.joinToString("、") { it.stripTrailingZeros().toPlainString() }
        return "模型给的金额和原话对不上（模型报 $got，原话是 $want）"
    }

    fun check(raw: String, parsedAmount: String?): Result {
        val strong = moneyIn(raw)
        val expected = strong.ifEmpty { numbersIn(raw) }
        if (expected.isEmpty()) return Result.NothingToCheck

        val parsed = parsedAmount?.trim()?.toBigDecimalOrNull()
        // 解析不出金额也算对不上：原文明明有数字，结果却没有金额可用
        if (parsed == null) return Result.Mismatch(null, expected)

        // 比的是量：符号是方向（见类注释），两个都取绝对值
        val magnitude = parsed.abs()
        // compareTo 而不是 equals：3.5 与 3.50 是同一个金额，只是写法不同
        return if (expected.any { it.compareTo(magnitude) == 0 }) Result.Match
        else Result.Mismatch(parsed, expected)
    }

    /** 文本里**明确带货币单位或符号**的数字。中文与阿拉伯都算。 */
    fun moneyIn(raw: String): List<BigDecimal> = buildList {
        ARABIC.findAll(raw).forEach { m ->
            val symbol = m.value.first() == '¥' || m.value.first() == '￥'
            if (symbol || m.groupValues[2].isNotEmpty()) {
                decimal(m.groupValues[1])?.let(::add)
            }
        }
        addAll(chineseMoneyIn(raw))
    }.distinct()

    /** 文本里出现的所有数字（含没有单位的）。 */
    fun numbersIn(raw: String): List<BigDecimal> = buildList {
        ARABIC.findAll(raw).forEach { m -> decimal(m.groupValues[1])?.let(::add) }
        addAll(chineseMoneyIn(raw))
    }.distinct()

    // —— 阿拉伯数字 ——

    /**
     * 可选货币符号 + 数字（可带千分位与小数）+ 可选货币单位。
     * `group(1)` 是数字本身，`group(2)` 是单位（空 = 没写单位）。
     */
    private val ARABIC = Regex(
        """[¥￥]?\s*(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)""" +
            """\s*(元|块钱|块|圆|毛|角|分)?""",
    )

    private fun decimal(raw: String): BigDecimal? =
        raw.replace(",", "").toBigDecimalOrNull()

    // —— 中文数字 ——

    /**
     * 「五十块」「三块五」「两块五毛」。**前面不许再挨着中文数字或百千万亿**——
     * 否则「一千二百块」会被从「二百」处切开，算出 200 而实际是 1200，
     * 变成一次误判。
     */
    private val CN_YUAN = Regex(
        "(?<![零一二两三四五六七八九十百千万亿])" +
            "([零一二两三四五六七八九十]+)\\s*(?:块钱|块|元|圆)" +
            "(?:\\s*([零一二两三四五六七八九])\\s*(?:毛|角)?)?",
    )

    /** 「五毛」「三分」——单位本身就是小数位。 */
    private val CN_FRACTION = Regex(
        "(?<![零一二两三四五六七八九十百千万亿])" +
            "([零一二两三四五六七八九])\\s*(毛|角|分)",
    )

    private fun chineseMoneyIn(raw: String): List<BigDecimal> = buildList {
        CN_YUAN.findAll(raw).forEach { m ->
            val yuan = cnInt(m.groupValues[1]) ?: return@forEach
            val fraction = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::cnInt) ?: 0
            // 「三块五」的「五」是十分位，不是五元。分数位只认单个中文数字：
            // 认「五十」会把 3.50 算成 3+5.0，而 3.5 与 3.50 本来就相等，
            // 多认反而制造误判。
            add(BigDecimal(yuan).add(BigDecimal(fraction).movePointLeft(1)))
        }
        CN_FRACTION.findAll(raw).forEach { m ->
            val n = cnInt(m.groupValues[1]) ?: return@forEach
            val scale = if (m.groupValues[2] == "分") 2 else 1
            add(BigDecimal(n).movePointLeft(scale))
        }
    }.distinct()

    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '两' to 2, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /**
     * 只认「X」「十」「十X」「X十」「X十Y」这几种形态，**认不出来返回 null**。
     *
     * `百`/`千`/`万` 一律不认（它们不在 [CN_DIGITS] 里，多字符形态也走不到）——
     * 见类注释里那条刻意的取舍。
     */
    private fun cnInt(text: String): Int? {
        val s = text.trim()
        fun digit(c: Char) = CN_DIGITS[c]
        return when {
            s.length == 1 -> digit(s[0])
            s == "十" -> 10
            s.length == 2 && s[0] == '十' -> digit(s[1])?.let { 10 + it }
            s.length == 2 && s[1] == '十' -> digit(s[0])?.let { it * 10 }
            s.length == 3 && s[1] == '十' ->
                digit(s[0])?.let { tens -> digit(s[2])?.let { tens * 10 + it } }
            else -> null
        }
    }
}
