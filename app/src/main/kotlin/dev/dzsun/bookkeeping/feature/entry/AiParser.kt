package dev.dzsun.bookkeeping.feature.entry

/**
 * AI 从自然语言抽取出来的**一笔**待确认账目。
 *
 * 这是「解析结果卡片」的展示模型，不是最终入库的凭证——
 * 用户确认/修改后才走 [dev.dzsun.bookkeeping.core.ledger.JournalDraft] 落账。
 */
data class ParsedEntry(
    val id: String,
    val kind: EntryKind,
    val amountText: String,
    val categoryName: String,
    val payee: String,
    val note: String,
    val dateOffsetDays: Int = 0,
    val confidence: Float = 1f,
) {
    val isHighConfidence: Boolean get() = confidence >= 0.8f
}

/**
 * 自然语言 → [ParsedEntry] 的解析接口。
 *
 * 当前实现是本地规则版 [LocalAiParser]，仅用于把 UI 跑起来；
 * 等真正的 AI 接口就绪后，换一个实现即可，UI 不用动。
 */
interface AiParser {
    suspend fun parse(raw: String): List<ParsedEntry>
}

/**
 * 本地规则解析器（**临时占位**，只保证 UI 能跑通）。
 *
 * 按约定第二节第 4 条，判定最终应交给调度层（`DispatcherClient`）。
 * 这里只做离线降级：分类名已对齐 `default_accounts.json`，
 * 接上 `DispatcherClient` 后整类替换即可，UI 不用动。
 */
class LocalAiParser : AiParser {

    override suspend fun parse(raw: String): List<ParsedEntry> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        // 按句切分：分号、换行、以及「，」连接的独立子句
        val chunks = text
            .split(Regex("[；;\\n]|(?<=[，,。！？!?])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return chunks.mapIndexedNotNull { index, chunk -> parseOne(chunk, index) }
    }

    private fun parseOne(chunk: String, index: Int): ParsedEntry? {
        val amount = extractAmount(chunk) ?: return null
        val kind = detectKind(chunk)
        val category = detectCategory(chunk, kind)
        val payee = detectPayee(chunk, category)
        val dateOffset = detectDateOffset(chunk)
        val confidence = if (category.isHighConfidence) 0.9f else 0.6f

        return ParsedEntry(
            id = "parsed-$index",
            kind = kind,
            amountText = amount,
            categoryName = category.name,
            payee = payee,
            note = chunk,
            dateOffsetDays = dateOffset,
            confidence = confidence,
        )
    }

    private fun extractAmount(text: String): String? {
        val patterns = listOf(
            Regex("(\\d+(?:\\.\\d{1,2})?)\\s*元"),
            Regex("[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Regex("(?:花了?|付了?|支出|消费|转了?|到账|收入|工资|发了?)\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Regex("(\\d+(?:\\.\\d{1,2})?)"),
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun detectKind(text: String): EntryKind {
        val incomeWords = listOf("工资", "到账", "收入", "红包", "报销", "退款", "奖金", "利息", "转入")
        return if (incomeWords.any { text.contains(it) }) EntryKind.INCOME else EntryKind.EXPENSE
    }

    private data class CategoryHit(val name: String, val isHighConfidence: Boolean)

    private fun detectCategory(text: String, kind: EntryKind): CategoryHit {
        // 分类名必须能在 default_accounts.json 的 account 表里按 name 找到，
        // 否则用户点确认时 categoryId 映射会落空。
        if (kind == EntryKind.INCOME) {
            val incomeMap = listOf(
                "工资" to "工资",
                "奖金" to "奖金",
                "利息" to "投资收益",
                "理财" to "投资收益",
                "红包" to "其他收入",
                "报销" to "其他收入",
                "退款" to "其他收入",
                "转入" to "其他收入",
            )
            for ((k, v) in incomeMap) {
                if (text.contains(k)) return CategoryHit(v, true)
            }
            return CategoryHit("其他收入", false)
        }

        val expenseMap = listOf(
            "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通", "停车" to "交通", "高铁" to "交通",
            "午饭" to "餐饮", "晚饭" to "餐饮", "早餐" to "餐饮", "外卖" to "餐饮", "吃饭" to "餐饮",
            "咖啡" to "餐饮", "奶茶" to "餐饮", "火锅" to "餐饮", "烧烤" to "餐饮", "零食" to "餐饮",
            "房租" to "居住", "水费" to "居住", "电费" to "居住", "物业" to "居住", "网费" to "居住",
            "超市" to "购物", "淘宝" to "购物", "京东" to "购物", "衣服" to "购物", "鞋" to "购物",
            "电影" to "娱乐", "游戏" to "娱乐", "健身" to "娱乐", "旅游" to "娱乐", "门票" to "娱乐",
            "医院" to "医疗", "药店" to "医疗", "药" to "医疗", "挂号" to "医疗",
            "话费" to "通讯", "流量" to "通讯", "宽带" to "通讯",
            "学费" to "教育", "书" to "教育", "课程" to "教育",
            "红包" to "人情往来", "份子" to "人情往来", "礼物" to "人情往来",
        )
        for ((k, v) in expenseMap) {
            if (text.contains(k)) return CategoryHit(v, true)
        }
        return CategoryHit("其他支出", false)
    }

    private fun detectPayee(text: String, category: CategoryHit): String {
        // 尝试提取「在XX」「给XX」「XX店」形式的商家
        val patterns = listOf(
            Regex("在\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})"),
            Regex("给\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})"),
            Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{2,8}(?:店|餐厅|超市|咖啡|药房|医院))"),
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return category.name
    }

    private fun detectDateOffset(text: String): Int = when {
        text.contains("昨天") -> -1
        text.contains("前天") -> -2
        else -> 0
    }
}
