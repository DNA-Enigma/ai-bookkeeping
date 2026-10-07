package dev.dzsun.bookkeeping.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从任务产物里抽「商户 → 分类名」。
 *
 * ⚠️ **这个能力的产物 schema 还没定**（`schemas/` 下没有对应文件），所以这里是
 * 容错解析——把几种合理形状都认下来。容错是过渡手段，不是终态：定形之后应当
 * 收敛成唯一形状，那时这些「多认几个键」的分支就该删掉。
 *
 * 但有一条现在就要钉死：**认不出来时返回空表**，绝不猜。界面据此说
 * 「调度层没给出建议」，而不是拿一个编出来的分类去改用户的账。
 */
class MerchantCategorizationWireTest {

    private fun result(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `数组形状，挂在 suggestions 下`() {
        val parsed = parseMerchantCategories(
            result(
                """
                {"suggestions":[
                  {"merchant":"星巴克","category":"餐饮","confidence":0.9},
                  {"merchant":"滴滴出行","category":"交通"}
                ]}
                """,
            ),
        )
        assertEquals(mapOf("星巴克" to "餐饮", "滴滴出行" to "交通"), parsed)
    }

    @Test
    fun `挂在 artifacts main 下也认得出来`() {
        val parsed = parseMerchantCategories(
            result(
                """{"artifacts":{"main":{"suggestions":[{"merchant":"盒马","category":"购物"}]}}}""",
            ),
        )
        assertEquals(mapOf("盒马" to "购物"), parsed)
    }

    @Test
    fun `对象映射形状，商户名直接做键`() {
        val parsed = parseMerchantCategories(
            result("""{"main":{"merchant_categories":{"星巴克":"餐饮","滴滴":"交通"}}}"""),
        )
        assertEquals(mapOf("星巴克" to "餐饮", "滴滴" to "交通"), parsed)
    }

    @Test
    fun `值是小对象的映射形状也收`() {
        val parsed = parseMerchantCategories(
            result("""{"main":{"categories":{"星巴克":{"category":"餐饮"}}}}"""),
        )
        assertEquals(mapOf("星巴克" to "餐饮"), parsed)
    }

    @Test
    fun `商户名与分类名的备用键都认`() {
        val parsed = parseMerchantCategories(
            result("""{"suggestions":[{"payee":"星巴克","label":"餐饮"}]}"""),
        )
        assertEquals(mapOf("星巴克" to "餐饮"), parsed)
    }

    // —— 认不出来时不许编 ——

    @Test
    fun `产物里没有归类时返回空表`() {
        assertTrue(parseMerchantCategories(result("{}")).isEmpty())
        assertTrue(parseMerchantCategories(result("""{"main":{"entries":[],"count":0}}""")).isEmpty())
    }

    @Test
    fun `不是归类的对象不会被误认成归类`() {
        // 凭证那条产物有 merchant 与 category，但它是**一笔账**，不是归类建议。
        // 不过它不在 suggestions / merchant_categories 这些容器键下面，所以认不到。
        val parsed = parseMerchantCategories(
            result("""{"main":{"entry_id":"task_x:main","merchant":"星巴克","category":"餐饮"}}"""),
        )
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `数组里缺分类名的条目被跳过，不落成空值`() {
        val parsed = parseMerchantCategories(
            result("""{"suggestions":[{"merchant":"星巴克"},{"merchant":"滴滴","category":"交通"}]}"""),
        )
        assertEquals(mapOf("滴滴" to "交通"), parsed)
    }

    @Test
    fun `空数组与空对象都返回空表`() {
        assertTrue(parseMerchantCategories(result("""{"suggestions":[]}""")).isEmpty())
        assertTrue(parseMerchantCategories(result("""{"suggestions":{}}""")).isEmpty())
    }
}
