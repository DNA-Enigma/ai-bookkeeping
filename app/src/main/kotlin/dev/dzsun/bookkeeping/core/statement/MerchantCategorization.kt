package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType

/**
 * 一次导入里冒出来的、**用户从没归过类**的商户。
 *
 * 判据是 [PlannedEntry.categoryFromHistory]：为 false 表示这一行落到了兜底分类
 * （「其他支出」/「其他收入」），而不是按用户自己的历史归的类。流水本身不带分类，
 * 所以「陌生」的定义只能是「本机历史里查不到」。
 */
data class UnknownMerchant(
    val payee: String,
    val direction: StatementDirection,
    /** 这份流水里出现几次。用户按商户做决定，「星巴克出现 12 次」比 12 条一样的行有用。 */
    val occurrences: Int,
)

/**
 * 调度层给出的归类建议，**尚未落库**。
 *
 * [categoryId] 为 null 表示名字对不上用户的科目表——那时**不能猜**，
 * 让用户在科目表里自己挑一个。
 */
data class MerchantSuggestion(
    val payee: String,
    val direction: StatementDirection,
    val categoryName: String?,
    val categoryId: String? = null,
    val confidence: Float? = null,
) {
    /** 已经对上了科目表里的某个分类账户，可以直接采纳。 */
    val resolved: Boolean get() = categoryId != null
}

/** 一次批量归类的结局。 */
sealed interface MerchantCategorization {
    data class Suggested(val suggestions: List<MerchantSuggestion>) : MerchantCategorization

    /**
     * 拿不到建议：调度层没起、契约里还没有这个能力、或超时。
     *
     * **这不是错误，是能力不可用。** 界面照常让用户手工挑分类即可，
     * 不该弹一个用户无能为力的错误框——那只会让他以为是自己操作错了。
     */
    data class Unavailable(val reason: String) : MerchantCategorization
}

/**
 * 把陌生商户列表**一次**交给调度层归类。
 *
 * 「一次」是有约束力的（约定 7）：几百个商户逐个提交任务既慢又贵，
 * 而且每个任务都要单独走一遍评估与路由。所以接口收的是列表，不是单个商户。
 *
 * 分类名从**调用方的科目表**传给调度层——「分类是数据不是代码」，
 * 调度层不认识用户自己的体系，只能从传进去的集合里选。
 */
interface MerchantCategorizer {
    suspend fun categorize(
        merchants: List<UnknownMerchant>,
        categories: List<String>,
    ): MerchantCategorization
}

/**
 * 没有调度层时用的兜底实现。
 *
 * **刻意不做本地关键词归类**：约定 4 明写客户端不做关键词匹配，
 * 而且一份写死的映射表迟早和用户的科目表对不上（这正是 `LocalAiParser` 踩过的坑）。
 * 认不出就如实说认不出。
 */
object UnavailableMerchantCategorizer : MerchantCategorizer {
    override suspend fun categorize(
        merchants: List<UnknownMerchant>,
        categories: List<String>,
    ): MerchantCategorization = MerchantCategorization.Unavailable("未接入调度层")
}

// ---------------------------------------------------------------- 纯函数

/**
 * 找出这次导入里「新出现、没有分类」的商户。
 *
 * 只看 [ImportPlan.entries]——那是**真正会入库**的行。疑似重复的默认不导入，
 * 拿它们的商户去归类，等于为一批不在账本里的行花钱调模型。
 *
 * 同一商户出现多次只出一条。商户若在收支两个方向都出现过，取**第一次**出现的方向：
 * 同一个对手方既收又支是常见情形（退款、报销），逐方向拆成两条建议反而更难选。
 */
fun ImportPlan.unknownMerchants(): List<UnknownMerchant> {
    val byPayee = LinkedHashMap<String, UnknownMerchant>()
    for (entry in entries) {
        if (entry.categoryFromHistory) continue
        val payee = entry.row.merchant?.trim().orEmpty()
        if (payee.isEmpty()) continue
        val seen = byPayee[payee]
        byPayee[payee] = if (seen == null) {
            UnknownMerchant(payee = payee, direction = entry.row.direction, occurrences = 1)
        } else {
            seen.copy(occurrences = seen.occurrences + 1)
        }
    }
    return byPayee.values.toList()
}

/**
 * 把建议里的分类名对到用户科目表里的分类账户。
 *
 * 名字对不上时**不猜**——[MerchantSuggestion.categoryId] 留 null，界面让用户自己挑。
 * 这正是「分类是数据不是代码」的落点：调度层只给名字，账户 id 只有本机科目表知道。
 *
 * 优先在**与收支方向同类**的账户里找；找不到再按名字全局找一次（用户的科目表里
 * 收入与支出分类偶尔同名，方向是更可靠的线索）。
 */
fun List<MerchantSuggestion>.resolveCategories(
    categories: List<AccountEntity>,
): List<MerchantSuggestion> = map { suggestion ->
    val wanted = suggestion.categoryName?.trim().orEmpty()
    if (wanted.isEmpty()) {
        suggestion.copy(categoryId = null)
    } else {
        val match = categories.firstOrNull { it.type == suggestion.direction.accountType && it.name == wanted }
            ?: categories.firstOrNull { it.name == wanted }
        suggestion.copy(categoryId = match?.id)
    }
}

/** 这个方向的流水该落在哪一类账户上。 */
val StatementDirection.accountType: AccountType
    get() = when (this) {
        StatementDirection.EXPENSE -> AccountType.EXPENSE
        StatementDirection.INCOME -> AccountType.INCOME
        // 不计收支的行在解析阶段就被判成 NotConsumption，进不了 ImportPlan.entries
        StatementDirection.NEUTRAL -> error("不计收支的行没有对应的分类账户")
    }
