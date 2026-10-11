package dev.dzsun.bookkeeping.feature.ask

import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import dev.dzsun.bookkeeping.core.ledger.QueryRange
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 本机模型对一句问话的判定结果。
 *
 * 三态，不是两态：**[Invalid] 必须与 [Chatter] 分开**。
 * 两者都会让调用方退回规则，但理由完全不同——一个是「模型说了这不是问账」，
 * 另一个是「模型这句没听懂」。合成一个的话，界面就只能说一句含糊的
 * 「这次按规则处理」，用户没法知道该不该去加载模型。
 */
sealed interface QueryVerdict {
    /** 这是一句问账，条件已经抽出来了。 */
    data class Ledger(val query: LedgerQuery) : QueryVerdict

    /** 模型判定：不是在问账本（闲聊、要建议、报一笔账……）。 */
    data object Chatter : QueryVerdict

    /** 模型输出不合法（不是 JSON / intent 认不出来 / 时间词认不出来）。[reason] 原样上报。 */
    data class Invalid(val reason: String) : QueryVerdict
}

/** 模型输出用的解析器：允许前后有杂音，只要外层是对象。 */
private val verdictJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 把 [dev.dzsun.bookkeeping.llm.SparkSession.MODE_QUERY] 的输出翻成 [QueryVerdict]。
 *
 * **纯函数**：没有 Android、没有数据库、没有当前时间——[today] 与 [categoryNames]
 * 都从参数进来，所以每一条映射规则都能在纯 JVM 测试里逐条钉死。
 *
 * ## 三条刻意的口径
 *
 * 1. **时间词只认符号，不认日期**（[QueryRange]）。模型说 `this_month`，
 *    几号到几号由本地换算；模型要是真写了个日期，那就是不合法输出，走规则兜底。
 *    1.7B 模型算日期实测必错（服务端同款 bug：「这个月」被算成上个月）。
 * 2. **认不出来 ≠ 不设限**。`range` 缺省（或写 `all`）才是不设限；
 *    写了个认不出来的词返回 [QueryVerdict.Invalid]——静默当「不限时间」会把
 *    「这个月花了多少」答成一个有史以来的总数，用户看不出答的不是他问的。
 * 3. **`subject_kind` 说 category 但名字不在科目表里时，降级成商户**。
 *    名字本身是有用的（「星巴克」被误标成分类，当商户查照样查得到），
 *    丢掉它才是真的答不了。分类名从科目表读，**不写死**（约定 3）。
 */
internal fun parseQueryVerdict(
    output: String,
    today: LocalDate,
    categoryNames: Set<String> = emptySet(),
): QueryVerdict {
    val obj = firstJsonObject(output)
        ?: return QueryVerdict.Invalid("模型输出里没有 JSON 对象：${output.trim().take(80)}")

    when (obj.str("intent")?.lowercase()) {
        "query", "查账", "问账" -> Unit
        "chat", "闲聊", "聊天" -> return QueryVerdict.Chatter
        null -> return QueryVerdict.Invalid("模型没给 intent：${output.trim().take(80)}")
        else -> return QueryVerdict.Invalid("intent 认不出来：${obj.str("intent")}")
    }

    val direction = when (obj.str("direction")?.lowercase()) {
        null, "", "expense", "支出", "花" -> QueryDirection.EXPENSE
        "income", "收入", "挣" -> QueryDirection.INCOME
        "both", "收支", "结余", "净额" -> QueryDirection.BOTH
        else -> return QueryVerdict.Invalid("direction 认不出来：${obj.str("direction")}")
    }

    val rawRange = obj.str("range")
    val range = QueryRange.resolve(rawRange)
        ?: return QueryVerdict.Invalid("range 认不出来：$rawRange")
    val span = range.span(today)

    // 模型偶尔会把时间词塞进 subject（「这个月」）。它不是一个商户名，
    // 拿去做 LIKE 只会查出「没有记录」，看着像答了其实答错了。
    val subject = obj.str("subject")?.takeIf { it.isNotBlank() && QueryRange.resolve(it) == null }
    val kind = obj.str("subject_kind")?.lowercase()

    val groupBy = when (obj.str("group_by")?.lowercase()) {
        "category", "分类" -> QueryGrouping.CATEGORY
        "merchant", "商户" -> QueryGrouping.MERCHANT
        "month", "按月" -> QueryGrouping.MONTH
        // 认不出来就不分组：分组只决定要不要多给一份明细，
        // 猜错了顶多少一块，**不会让上面那个总数变错**，所以这里不判不合法。
        else -> QueryGrouping.NONE
    }

    return runCatching {
        LedgerQuery(
            fromEpochDay = span?.first,
            toEpochDay = span?.last,
            direction = direction,
            categoryName = subject?.takeIf { kind == "category" && it in categoryNames },
            merchant = subject?.takeIf { kind != "category" || it !in categoryNames },
            grouping = groupBy,
        )
    }.fold(
        onSuccess = { QueryVerdict.Ledger(it) },
        onFailure = { QueryVerdict.Invalid("条件组不成一次查询：${it.message}") },
    )
}

/** 取输出里第一个 JSON 对象；前后有杂音（解释、markdown 围栏）也认。 */
private fun firstJsonObject(output: String): JsonObject? {
    val s = output.indexOf('{')
    val e = output.lastIndexOf('}')
    if (s !in 0 until e) return null
    return runCatching { verdictJson.parseToJsonElement(output.substring(s, e + 1)) }
        .getOrNull() as? JsonObject
}

/** 取一个字段的字符串值；缺失 / null / 非字符串都算「没给」。 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && it.isString }
        ?.content
        ?.trim()
