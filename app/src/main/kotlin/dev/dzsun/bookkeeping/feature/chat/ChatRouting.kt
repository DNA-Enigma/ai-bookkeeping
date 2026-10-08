package dev.dzsun.bookkeeping.feature.chat

/**
 * 对话页把用户一句话送到哪条链路。
 *
 * 抽成纯函数是因为它决定了整页的行为，而它此前藏在 Composable 的一个局部函数里，
 * 测得着才怪——「你好」被扔进问账链路、回一句「没听懂」就是这么漏出去的。
 */
internal sealed interface ChatIntent {
    /** 省钱建议：不编数字，把用户引到能真答的问题上。 */
    data object Advice : ChatIntent

    /** 含金额 → 记一笔。[amountText] 是用户原样的数字串，刻意不转 Double。 */
    data class Record(val amountText: String) : ChatIntent

    /** 寒暄与应答：回一句自然接话，[reply] 里主动说明它能干什么。 */
    data class SmallTalk(val reply: String) : ChatIntent

    /** 问账：交给调度层/本地翻译 → Room 聚合 → 中文答案。 */
    data class Ask(val question: String) : ChatIntent
}

private val AMOUNT = Regex("\\d+(?:\\.\\d+)?")

/** 打招呼。中英分开是因为英文要词边界，否则 `hi` 会在 `this`/`history` 里命中。 */
private val GREETINGS = listOf(
    "你好", "您好", "在吗", "在么", "在不在", "嗨", "哈喽", "哈啰",
    "早上好", "中午好", "下午好", "晚上好",
    "hi", "hello", "hey",
)

/** 应答与道别。回一句自然接话，不是敷衍。 */
private val ACKS = listOf(
    "谢谢", "多谢", "感谢", "好的", "好嘞", "收到", "晚安", "再见", "拜拜",
    "thanks", "thank you", "bye",
)

/**
 * 寒暄的接话。**必须主动告诉用户它能干什么**——用户打个招呼正是不知道
 * 这个入口能干嘛的时候，回一句「你好呀」等于把话头接死了。
 */
internal const val GREETING_REPLY =
    "在的。想记账直接说「打车 28」，想问账目就说「这个月餐饮花了多少」。"

internal const val ACK_REPLY =
    "不客气。说「午饭 35」我就记一笔，问「这个月花了多少」我就给你算。"

/**
 * 问账答不出来时的话。
 *
 * 不能只说「换个问法」了事——用户不知道换成哪种问法。所以**给出一个必然能成的例子**。
 */
internal fun askUnavailableReply(reason: String): String =
    "$reason\n\n" +
        "我能答的是账目问题，比如「这个月餐饮花了多少」「哪类花得最多」；" +
        "记账直接说「打车 28」。"

/**
 * 一句话该走哪条链路。
 *
 * 顺序有讲究，**含金额的判断不能被寒暄顶掉**：「你好我花了 35」是记账，不是寒暄。
 * 所以先把金额挑出来，剩下的才轮到寒暄与问账。
 */
internal fun classifyChatInput(text: String): ChatIntent {
    val trimmed = text.trim()

    if (trimmed.contains("省") || trimmed.contains("建议") || trimmed.contains("存钱")) {
        return ChatIntent.Advice
    }

    AMOUNT.find(trimmed)?.let { return ChatIntent.Record(it.value) }

    // 寒暄排在金额之后：先把「你好我花了 35」这类挑走，剩下的招呼才是招呼。
    if (matchesAny(trimmed, ACKS)) return ChatIntent.SmallTalk(ACK_REPLY)
    if (matchesAny(trimmed, GREETINGS)) return ChatIntent.SmallTalk(GREETING_REPLY)

    return ChatIntent.Ask(trimmed)
}

private fun matchesAny(text: String, words: List<String>): Boolean = words.any { word ->
    if (word.all { it.code < 0x80 }) {
        Regex("(?<![a-z0-9])${Regex.escape(word)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    } else {
        text.contains(word)
    }
}
