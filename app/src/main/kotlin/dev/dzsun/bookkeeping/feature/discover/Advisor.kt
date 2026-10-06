package dev.dzsun.bookkeeping.feature.discover

/**
 * 问诊出口。真实调度层/MCP 就绪前用 [LocalAdvisor]，
 * 页面只依赖本接口，换实现不动 UI。
 */
interface Advisor {
    suspend fun ask(message: ChatMessage): String
}

data class ChatMessage(
    val text: String,
    val imageBytes: ByteArray? = null,
    val imageMime: String = "image/jpeg",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatMessage) return false
        return text == other.text && imageMime == other.imageMime &&
            (imageBytes ?: ByteArray(0)).contentEquals(other.imageBytes ?: ByteArray(0))
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        result = 31 * result + imageMime.hashCode()
        return result
    }
}

/**
 * 离线顾问：按问题意图给结构化建议。
 * 不做投资荐股，只做「先看什么」的决策脚手架——真建议走调度层。
 */
class LocalAdvisor : Advisor {

    override suspend fun ask(message: ChatMessage): String {
        val q = message.text.trim()
        val hasImage = message.imageBytes != null
        return when {
            hasImage && (q.contains("投") || q.contains("值")) ->
                "从截图看，先核三件事再决定：\n\n" +
                    "1️⃣ **标的是什么**——股票/基金/理财？截图里的产品名称和代码\n" +
                    "2️⃣ **费用与锁定期**——申购费、管理费、赎回限制\n" +
                    "3️⃣ **和你已有持仓重不重复**——别单押一个方向\n\n" +
                    "⚠️ 我不给买卖建议。需要的话我可以按你的账单算「能拿出多少闲钱」，避免借钱投资。"

            hasImage ->
                "收到截图。图里如果是**账单/持仓**，我可以帮你：\n" +
                    "· 拆出金额、产品、时间\n" +
                    "· 和账本里的投资账户对一对\n" +
                    "说一句你想看什么就行。"

            q.contains("投") || q.contains("股") || q.contains("基金") || q.contains("理财") ->
                "投资前建议按这个顺序过一遍：\n\n" +
                    "1. **先留应急金**（3–6 个月开销）\n" +
                    "2. **只用闲钱**，不影响吃饭房租\n" +
                    "3. **分散**，别 All-in 一个标的\n" +
                    "4. **写清楚退出条件**再入场\n\n" +
                    "要我按你目前的收支算一下「每月能定投多少」吗？"

            q.contains("花") || q.contains("省") || q.contains("预算") || q.contains("账") ->
                "可以帮你看看账本。直接说：\n" +
                    "· 「这周花哪了」\n" +
                    "· 「餐饮是不是超了」\n" +
                    "· 「还能花多少」\n\n" +
                    "或者拍一张账单截图丢过来。"

            q.isBlank() -> "说说你想问什么，或拍一张截图过来。"

            else ->
                "我是记账与理财观察助手，擅长：\n" +
                    "· 看懂账单/持仓截图\n" +
                    "· 收支分析与预算\n" +
                    "· 投资前该查什么（不做荐股）\n\n" +
                    "你可以接着问，或者丢一张图。"
        }
    }
}
