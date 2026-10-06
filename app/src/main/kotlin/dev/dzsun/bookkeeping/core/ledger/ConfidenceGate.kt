package dev.dzsun.bookkeeping.core.ledger

/**
 * 「这笔识别得够不够可信，能不能不问用户就直接入账」。
 *
 * 产品决定（路线图 P0-a）：高置信直接入账（事后可撤销/编辑），低置信才弹确认卡。
 * 判定属于**数据层**——界面只读结论，不自建判定标准。两处各定一个阈值，
 * 迟早会出现「确认页说要确认、入账却已经自动记了」这种自相矛盾的状态。
 *
 * 纯函数，没有 Android 依赖，所以三种情形（达阈值 / 低于阈值 / 拿不到）能直接单测。
 * 阈值的**默认值**不在这里，在 [EntryRuleCatalog] 读的 assets 配置里；
 * 这里只放兜底与合法范围。
 */
object ConfidenceGate {

    /**
     * 配置读不到时的兜底阈值。
     *
     * 取 0.85：`extract` 实测常见 0.9~0.98，这个数放得过大多数正常票据，
     * 又拦得住「识别得勉强」的那些。**它是兜底不是真源**——
     * 真源在 `assets/entry_rules.json`，路线图写明「先定 0.85 起步实测调」。
     */
    const val DEFAULT_THRESHOLD = 0.85f

    /** 下限：再低就等于什么都不问，那等于关掉确认。 */
    const val MIN_THRESHOLD = 0.5f

    /** 上限 1.0：等于「永不自动入账」，是一个合法的保守选择。 */
    const val MAX_THRESHOLD = 1.0f

    /**
     * 要不要让用户确认。
     *
     * **[confidence] 为 null 一律要确认。** 这一条是这块最要紧的：
     * null 不是"未知但大概没事"，而是"我们真的不知道"。把它当作可信，
     * 就等于在一整条路由（纯文本记账、以及事件流断掉后的快照回看）上
     * 静默地跳过用户确认——而那正是最需要人看一眼的情形。
     */
    fun requiresConfirmation(confidence: Float?, threshold: Float): Boolean =
        confidence == null || confidence < threshold

    /** 能不能直接入账。与 [requiresConfirmation] 互补，取个正向的名字给调用方读着顺。 */
    fun canAutoPost(confidence: Float?, threshold: Float): Boolean =
        !requiresConfirmation(confidence, threshold)

    /** 把用户设的阈值收进合法范围。越界的值一律夹住，而不是报错。 */
    fun clamp(threshold: Float): Float = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
}
