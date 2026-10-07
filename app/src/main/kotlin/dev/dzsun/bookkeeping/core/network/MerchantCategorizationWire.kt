package dev.dzsun.bookkeeping.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 批量归类任务的载荷。**一次任务带一整份商户列表**，不是每个商户一个任务
 * （约定 7）——几百个商户逐个提交，每个都要走一遍评估与路由，既慢又贵。
 *
 * 分类词表也跟着走：调度层不认识用户自己的体系，只能从传进去的集合里选。
 */
@Serializable
internal data class MerchantCategorizeRequest(
    val merchants: List<String>,
    val categories: List<String>,
)

/**
 * 批量归类的能力名。
 *
 * ⚠️ **它目前不在调度层的 `config/taxonomy.yaml` 里**，因此现在声明它会被
 * 评估器当作未知类型忽略，任务多半落到兜底路由上。客户端的职责是**按约定 7 的
 * 形状发一次调用**，能力本身要后端加（见 `docs/dispatcher-issues.md` 的 P1-c）。
 */
internal const val INTENT_CATEGORIZE_MERCHANTS = "bookkeeping.categorize_merchants"

/** 建议可能挂在这些键下面。契约未定形，所以多认几个。 */
private val CATEGORY_CONTAINER_KEYS = listOf(
    "suggestions",
    "merchant_categories",
    "merchantCategories",
    "merchants",
    "categories",
)

private val MERCHANT_KEYS = listOf("merchant", "merchant_name", "payee", "name")
private val CATEGORY_VALUE_KEYS = listOf("category", "category_name", "label", "value")

/**
 * 从任务产物里抽出「商户 → 分类名」。
 *
 * ⚠️ 这个能力的产物 schema **还没定**（契约里没有 `bookkeeping.*` 的对应条目），
 * 所以这里按几种合理形状容错解析，而不是写死一种。定形之后应把它收敛成唯一形状——
 * 容错解析是过渡手段，不是终态。
 *
 * **必须挂在一个容器键下面**（`suggestions` / `merchant_categories` / …），
 * 不把「一个全是字符串值的对象」直接当成映射：凭证产物 `main` 也长那样
 * （`{"merchant":"星巴克","category":"餐饮"}`），认错就会把一条记账产物
 * 读成一份归类建议，然后拿 `entry_id` 当商户名去改账。
 *
 * 认不出来时返回空表：调用方据此报「调度层没给出建议」，**不编造归类**。
 */
internal fun parseMerchantCategories(root: JsonObject): Map<String, String> {
    for (node in root.nestedObjects()) {
        for (key in CATEGORY_CONTAINER_KEYS) {
            when (val value = node[key]) {
                is JsonArray -> value.toMerchantCategoryMap()?.let { return it }
                is JsonObject -> value.toMerchantCategoryMap()?.let { return it }
                else -> Unit
            }
        }
    }
    return emptyMap()
}

/** 数组形状：`[{"merchant":"星巴克","category":"餐饮"}, …]`。 */
private fun JsonArray.toMerchantCategoryMap(): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    for (element in this) {
        val obj = element as? JsonObject ?: return null
        val merchant = MERCHANT_KEYS.firstNotNullOfOrNull { obj.stringAt(it) } ?: continue
        val category = CATEGORY_VALUE_KEYS.firstNotNullOfOrNull { obj.stringAt(it) } ?: continue
        out[merchant] = category
    }
    return out.ifEmpty { null }
}

/**
 * 对象形状，两种都收：
 * - `{"星巴克":"餐饮", …}` —— 商户名直接做键
 * - `{"星巴克":{"category":"餐饮"}, …}` —— 值是个小对象
 */
private fun JsonObject.toMerchantCategoryMap(): Map<String, String>? {
    if (isEmpty()) return null
    val out = LinkedHashMap<String, String>()
    for ((key, value) in this) {
        val category = when (value) {
            is JsonPrimitive -> value.contentOrNull
            is JsonObject -> CATEGORY_VALUE_KEYS.firstNotNullOfOrNull { value.stringAt(it) }
            else -> null
        } ?: return null
        out[key] = category
    }
    return out.ifEmpty { null }
}

private fun JsonObject.stringAt(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
