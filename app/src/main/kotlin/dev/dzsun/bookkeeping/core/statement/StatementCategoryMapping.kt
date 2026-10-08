package dev.dzsun.bookkeeping.core.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条「账单交易分类 → 本账本分类名」的映射。
 *
 * **只写分类名不写 id**：账户 id 是本机科目表的事，配置文件里写死 id 的话，
 * 用户改一次分类名或删掉一个分类，映射就指向一个不存在的账户了。
 * 名字对不上时如实落到兜底分类，不猜。
 */
@Serializable
data class StatementCategoryRule(
    /** 本账本里的分类名，如「餐饮」。 */
    val category: String,
    /** 账单「交易分类」列里可能出现的词。命中任意一个即归到 [category]。 */
    val aliases: List<String>,
)

@Serializable
internal data class StatementCategoryFile(
    val version: Int,
    val rules: List<StatementCategoryRule>,
)

/**
 * 用账单自带的「交易分类」列推一个分类账户。
 *
 * 账单其实**自带正确的分类**（支付宝的「交易分类」列写着「餐饮美食」「交通出行」），
 * 此前这份信息只被用于排除与退款判断，从没用来过定分类，于是整批落到「其他支出」。
 * 这里把它用起来，作为**第二档**：用户对该商户的历史归类仍然优先。
 *
 * 三条刻意的口径：
 * 1. **只在与收支方向同类的账户里找**——「餐饮」是支出分类，收入行不可能映射到它；
 * 2. **别名做包含匹配**——账单的取值是「餐饮美食」，比配置里的「餐饮」长；
 * 3. **认不出返回 null**，交给调用方落到兜底。绝不能拿一个相近的词硬套，
 *    那会让用户以为分类是账单给的，其实是我们猜的。
 */
internal fun matchStatementCategory(
    rawType: String?,
    direction: StatementDirection,
    rules: List<StatementCategoryRule>,
    categories: List<AccountEntity>,
): AccountEntity? {
    val type = rawType?.trim().orEmpty()
    if (type.isEmpty()) return null
    // 不计收支的行在解析阶段就被判成 NotConsumption，走不到这里；防御性地放行，
    // 免得一个纯粹的映射函数因为方向而抛异常。
    val wanted = when (direction) {
        StatementDirection.EXPENSE -> AccountType.EXPENSE
        StatementDirection.INCOME -> AccountType.INCOME
        StatementDirection.NEUTRAL -> return null
    }
    for (rule in rules) {
        if (rule.aliases.none { it.isNotBlank() && type.contains(it.trim()) }) continue
        val name = rule.category.trim()
        categories.firstOrNull { it.type == wanted && it.name == name }?.let { return it }
    }
    return null
}

/**
 * 从 assets 读分类映射。加了新词只要改 JSON，这里一行都不用动。
 *
 * 读失败时返回空列表而不是抛——**空列表会让所有行落到兜底分类，那是对的**；
 * 抛异常则会让整份导入在拿到文件之前就崩掉。
 */
@Singleton
class StatementCategoryCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: List<StatementCategoryRule>? = null

    /** 全部映射规则，按配置里的先后顺序。 */
    fun rules(): List<StatementCategoryRule> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): List<StatementCategoryRule> = runCatching {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        json.decodeFromString<StatementCategoryFile>(text).rules
    }.getOrDefault(emptyList())

    companion object {
        const val ASSET_NAME = "statement_categories.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
