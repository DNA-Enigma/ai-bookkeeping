package dev.dzsun.bookkeeping.llm

/**
 * 一次判定的来源。只有两种，**问账页脚注 / 对话页气泡 / 解析卡片三处都按它渲染**。
 */
enum class SourceKind {
    /** 本机模型（SparkSession，离线）判定的。 */
    MODEL,

    /** 规则兜底 —— 模型没接上或输出不合法时，才允许走到这里。 */
    RULES,
}

/**
 * 统一的来源标注。
 *
 * ## 为什么要一个类型，而不是几个字符串常量
 *
 * 同一条口径要在三处说同一件事。上一版问账页脚注是「这句话是按本地规则理解的，
 * 没有经过 AI 服务」——**不带原因**，用户看完只会以为功能根本没接入，
 * 而实际只是这一次模型没就绪。三处各写各的，改一处必漏两处。
 *
 * ## 两条在类型上就定死的规矩
 *
 * 1. **RULES 必须带原因**（[reason] 非空，构造时校验）。「模型未接上（<原因>）」
 *    里的原因不许为空——空原因的规则标注**在类型上构造不出来**。
 * 2. **MODEL 不许带兜底原因**。带了说明调用方在两边都不确定自己在哪条路上。
 *
 * [code] 是机械判据触发的代码（如 `MODEL_AMOUNT_MISMATCH`），验收按它断言，
 * 界面上也显示——用户知道这次为什么不是模型给的。
 */
data class SourceTag(
    val kind: SourceKind,
    val reason: String? = null,
    val code: String? = null,
) {
    init {
        when (kind) {
            SourceKind.MODEL -> require(reason == null) { "MODEL 来源不该带兜底原因：$reason" }
            SourceKind.RULES -> require(!reason.isNullOrBlank()) { "RULES 来源必须说明为什么没走模型" }
        }
    }

    val isModel: Boolean get() = kind == SourceKind.MODEL

    /** 卡片与气泡上的短标签。 */
    val shortLabel: String get() = when (kind) {
        SourceKind.MODEL -> "本机模型"
        SourceKind.RULES -> "规则兜底"
    }

    /**
     * 脚注全文，**必须含 `src=<KIND>`** —— 验收要在结果里看到它。
     *
     * `—— [去加载模型]` 那个可点的入口由界面渲染（[isModel] 为 false 时），
     * 不进文案：文案要能被单测逐字钉住，按钮不是文字。
     */
    fun footnote(): String = when (kind) {
        SourceKind.MODEL -> "本机模型判定（src=MODEL）"
        SourceKind.RULES -> buildString {
            append("模型未接上（").append(reason).append("），本次按规则处理（src=RULES")
            code?.let { append("，").append(it) }
            append("）")
        }
    }

    companion object {
        val Model = SourceTag(SourceKind.MODEL)

        fun rules(reason: String, code: String? = null) = SourceTag(SourceKind.RULES, reason, code)

        /**
         * 「模型未接上」的标准原因串 —— **与 [issueMessage] 的文案同一套**。
         *
         * 单独拎出来是为了让三处入口说的是同一个原因，不是各编一句。
         */
        fun rulesOf(issue: ModelIssue, code: String? = null) = rules(issueMessage(issue), code)

        /** 模型还在准备 / 超时这一类「不是因为坏了，是还没好」的原因。 */
        fun rulesNotReady(reason: String, code: String? = null) = rules(reason, code)

        /** 金额与原文对不上——模型给了一个错数字，作废它的结果。 */
        const val CODE_AMOUNT_MISMATCH = "MODEL_AMOUNT_MISMATCH"

        /** 模型输出不是合法 JSON，补救重试也没救回来。 */
        const val CODE_BAD_OUTPUT = "MODEL_OUTPUT_INVALID"

        /** 模型回了话却一个记账条目都没给，卡片是规则补出来的。 */
        const val CODE_NO_ENTRIES = "MODEL_NO_ENTRIES"
    }
}
