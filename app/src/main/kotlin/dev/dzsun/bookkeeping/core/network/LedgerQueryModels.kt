package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.core.ledger.LedgerQuery
import dev.dzsun.bookkeeping.core.ledger.QueryDirection
import dev.dzsun.bookkeeping.core.ledger.QueryGrouping
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `bookkeeping.ledger.query` 的服务端产出：**把问题翻译成一次结构化查询**。
 *
 * ## 为什么服务端给的是「查询」而不是「答案」
 *
 * 账本在手机上的 Room 里，调度层看不到它。这不是推测，是实测：
 *
 * ```
 * POST /v1/tasks {"input":{"text":"这个月花了多少"},
 *                 "declared":{"intent":"bookkeeping.ledger.query"}}
 * → route_id: single_tool_action, tool_set: ["账目查询工具"], max_llm_calls: 0
 * → result.main = {"entries": [], "count": 0}
 * ```
 *
 * 那个账目查询工具读的是服务端自己的 `LedgerPort`（`handlers/bookkeeping/ports.py`，
 * 参考实现是进程内的 `InMemoryLedger`），所以它**恒等于空**——不管用户这个月花了多少。
 * 后端的工具注释自己也写明了：「账本在消费端的本地库里，后端的工具不可能写进用户手机的数据库」。
 * 读和写是同一个道理。
 *
 * 所以分工只能是：**调度层负责判定（把「星巴克花了几次」翻成下面这个结构），
 * 客户端负责算术（在本机 SQL 里聚合）**。这样也顺带满足了两条既有约定——
 * 判定不落在客户端的关键词表上，而账目一个字节都不出设备。
 *
 * ## 契约状态
 *
 * ⚠️ **这个形状目前是客户端单方面定义的，调度层还没有实现它**（`schemas/` 下没有
 * 对应文件，`handler.yaml` 里那个账目查询工具既没有 `input_schema` 也没声明产出形状）。
 * 见 `docs/dispatcher-issues.md` 的 P1-a 条。客户端已经按它实现完毕，
 * 后端落地后**不需要改界面**。
 */
@Serializable
data class LedgerQuerySpec(
    /** 起始日期，ISO `yyyy-MM-dd`。省略表示不设下限（「一共花了多少」）。 */
    val from: String? = null,
    /** 结束日期，ISO `yyyy-MM-dd`，闭区间。 */
    val to: String? = null,
    /**
     * 收支方向：`expense` / `income` / `both`。
     *
     * **必填。** 缺了它这条查询就不成立——默认成 `expense` 会把「我这个月收入多少」
     * 静默答成一个支出数字，而用户不会察觉自己看到的不是自己问的。
     */
    val direction: String? = null,
    /** 分类名，精确匹配。取自该用户的分类词表。 */
    val category: String? = null,
    /** 商户名，包含匹配。 */
    val merchant: String? = null,
    /** 分组维度：`none` / `category` / `merchant` / `month`。 */
    @SerialName("group_by") val groupBy: String? = null,
    /** 分组最多返回几行。省略则用 [LedgerQuery.DEFAULT_LIMIT]。 */
    val limit: Int? = null,
) {

    /**
     * 翻成本地可执行的查询。
     *
     * 返回 null 表示**这条 spec 不成立**（方向缺失或不可识别、日期格式错）。
     * 不猜、不给默认方向：宁可界面说「调度层没能把这个问题翻译清楚」，
     * 也不要拿一个方向都不确定的查询去算出一个看着挺像的错数字。
     *
     * 分组维度认不出来时**退回不分组**（[QueryGrouping.NONE]）而不是报错——
     * 合计仍然是正确的，少一层明细不至于骗人；这与方向的严格形成对比，
     * 因为方向错了数字就是错的，而分组只是详略。
     */
    fun toQuery(): LedgerQuery? {
        val resolvedDirection = direction?.lowercase()?.let(DIRECTIONS::get) ?: return null
        val fromDay = from?.let { parseDay(it) ?: return null }
        val toDay = to?.let { parseDay(it) ?: return null }
        if (fromDay != null && toDay != null && fromDay > toDay) return null

        return LedgerQuery(
            fromEpochDay = fromDay,
            toEpochDay = toDay,
            direction = resolvedDirection,
            categoryName = category?.takeIf { it.isNotBlank() },
            merchant = merchant?.takeIf { it.isNotBlank() },
            grouping = groupBy?.lowercase()?.let(GROUPINGS::get) ?: QueryGrouping.NONE,
            limit = limit?.takeIf { it > 0 } ?: LedgerQuery.DEFAULT_LIMIT,
        )
    }

    // companion 本身**不能是 private**：`@Serializable` 会把 `serializer()` 生成在这里，
    // 私有了外部就连 `LedgerQuerySpec.serializer()` 都调不到，反序列化整条路走不通。
    // 要藏的是这几个成员，不是这个 companion。
    companion object {
        private val DIRECTIONS = mapOf(
            "expense" to QueryDirection.EXPENSE,
            "income" to QueryDirection.INCOME,
            "both" to QueryDirection.BOTH,
        )

        private val GROUPINGS = mapOf(
            "none" to QueryGrouping.NONE,
            "category" to QueryGrouping.CATEGORY,
            "merchant" to QueryGrouping.MERCHANT,
            "month" to QueryGrouping.MONTH,
        )

        /** ISO 日期 → epoch day。格式不对时返回 null，由调用方判为「这条 spec 不成立」。 */
        private fun parseDay(text: String): Long? =
            runCatching { LocalDate.parse(text.trim()).toEpochDay() }.getOrNull()
    }
}

/**
 * 一次问账的结局。
 *
 * [Unavailable] 不是异常，是**当前调度层的真实状态**——它把问题路由对了
 * （`single_tool_action` + 账目查询工具），但产不出可执行的查询。
 * 界面据此如实说明，而不是编一个数出来。这与 `AutoEntryUndo` 的处理同一个道理：
 * **没有能力就不渲染按钮**，显示一个点不动的撤销键比不显示更糟。
 */
sealed interface LedgerQueryOutcome {
    val taskId: String?

    /** 把问题翻译成了一次查询，可以在本地执行了。 */
    data class Interpreted(
        override val taskId: String,
        val query: LedgerQuery,
    ) : LedgerQueryOutcome

    /** 没能拿到可执行的查询。[detail] 是给人看的原因，不参与任何判断。 */
    data class Unavailable(
        override val taskId: String?,
        val problem: Problem?,
        val detail: String,
    ) : LedgerQueryOutcome
}

/**
 * 从任务产物里取服务端下发的查询。
 *
 * 递归找**第一个能解析成合法 [LedgerQuerySpec] 的嵌套对象**——
 * 判别条件就是「有没有一个可识别的 `direction`」，因为那是 spec 唯一必填的字段
 * （与 `LedgerEntry` 认 `entry_id`、`ReceiptFields` 认 `amount`+`currency` 同一个路子）。
 *
 * 不假定产物挂在哪个键下：实测 `single_tool_action` 走的是 `artifacts.main`，
 * 而票据流程按节点名分（`extract`/`write`/…）。写死取 `main` 会在路由变化时
 * 静默变成「服务端没说」，而那与「服务端说了但读不出来」是两回事。
 */
internal fun JsonObject?.ledgerQuerySpec(): LedgerQuerySpec? =
    this?.nestedObjects()
        ?.filter { it.containsKey(KEY_DIRECTION) }
        ?.firstNotNullOfOrNull { obj ->
            runCatching { CaptureJson.decodeFromJsonElement(LedgerQuerySpec.serializer(), obj) }
                .getOrNull()
                ?.takeIf { it.toQuery() != null }
        }

/** 判别键。`direction` 是 spec 里唯一必填的字段，只有它能把 spec 与别的产物分开。 */
internal const val KEY_DIRECTION = "direction"

/**
 * `bookkeeping.ledger.query` 的意图名，取自 `config/taxonomy.yaml`。
 *
 * **这是契约词表的值，不是界面自己起的名字。** 与 `INTENT_RECEIPT` 是同一类东西。
 */
const val INTENT_LEDGER_QUERY = "bookkeeping.ledger.query"
