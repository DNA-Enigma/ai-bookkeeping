package dev.dzsun.bookkeeping.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这里的 JSON 全是**对着真实调度层跑出来的原文**（`127.0.0.1:8010`，2026-10-06），
 * 不是照文档编的。契约那四个 `bookkeeping.*` schema 曾经长期缺定义，
 * 形状是实测反推的；现在 schema 已冻结，这些用例就是"实现与契约没漂"的那道闸。
 */
class CaptureModelsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    // ---------- bookkeeping.ReceiptFields ----------

    @Test
    fun `extract 节点的真实产出可解析且金额不进浮点`() {
        // 原文来自收据截图任务的 subtask.completed（subtask_id = extract）
        val fields = json.decodeFromJsonElement(
            ReceiptFields.serializer(),
            obj(
                """
                {"amount": 38.5, "currency": "CNY", "merchant": "星巴克咖啡（国贸店）",
                 "datetime": "2026-10-06 14:32:07", "payment_method": "零钱",
                 "direction": "expense", "confidence": 0.9,
                 "notes": "商品说明：拿铁 大杯"}
                """,
            ),
        )

        assertEquals("星巴克咖啡（国贸店）", fields.merchant)
        assertTrue(fields.isExpense)
        assertTrue(fields.isHighConfidence)
        // 38.5 走 JSON 字面量 → BigDecimal → 最小单位整数，全程不经过 Double
        assertEquals(3850L, fields.money()?.amountMinor)
        assertEquals("CNY", fields.money()?.currency)
    }

    @Test
    fun `direction 为 income 时算收入，缺失时不默认成支出以外的东西`() {
        val income = ReceiptFields(amount = null, direction = "income")
        assertFalse(income.isExpense)
        // direction 缺失时 isExpense 为 true（记账默认按支出呈现），但金额缺了就是 null
        assertNull(ReceiptFields().money())
    }

    @Test
    fun `缺币种或金额读不出来时不编造金额`() {
        assertNull(ReceiptFields(amount = json.parseToJsonElement("38.5")).money())
        assertNull(ReceiptFields(currency = "CNY").money())
    }

    @Test
    fun `低置信度不算高置信`() {
        assertFalse(ReceiptFields(confidence = 0.72).isHighConfidence)
        // 没有置信度字段时按 0 处理——宁可不信，也不假装可信
        assertFalse(ReceiptFields().isHighConfidence)
    }

    // ---------- bookkeeping.LedgerEntry ----------

    @Test
    fun `单步工具路由的产物形状`() {
        // 原文来自纯文本任务：{"main": {"entry_id": "task_…:main", …}}
        val entry = obj(
            """
            {"main": {"entry_id": "task_9d14c06804714455b0bc:main", "amount": 38.0,
                      "currency": "CNY", "direction": "expense", "category": "餐饮",
                      "merchant": null, "occurred_at": null,
                      "source_task": "task_9d14c06804714455b0bc", "note": null}}
            """,
        ).ledgerEntry()

        assertEquals("task_9d14c06804714455b0bc:main", entry?.entryId)
        assertEquals("餐饮", entry?.category)
        assertEquals(3800L, entry?.toReceiptFields()?.money()?.amountMinor)
    }

    @Test
    fun `票据流程按节点分的产物形状`() {
        // 原文来自收据截图任务的成功快照：extract / normalize / dedupe / write 四个节点
        val artifacts = obj(
            """
            {"extract":  {"amount": 38.5, "currency": "CNY", "merchant": "星巴克咖啡（国贸店）",
                          "datetime": "2026-10-06 14:32:07", "payment_method": "零钱",
                          "direction": "expense", "confidence": 0.9,
                          "notes": "商品说明：拿铁 大杯"},
             "normalize": {"merchant_normalized": "星巴克", "category": "餐饮", "confidence": 0.75},
             "dedupe":   {"duplicate": false, "matched_entry_id": null},
             "write":    {"entry_id": "task_de72b0dfa2a248f19161:write", "amount": 38.5,
                          "currency": "CNY", "direction": "expense", "category": "餐饮",
                          "merchant": "星巴克咖啡（国贸店）",
                          "occurred_at": "2026-10-06 14:32:07",
                          "source_task": "task_de72b0dfa2a248f19161",
                          "note": "商品说明：拿铁 大杯"}}
            """,
        )

        val entry = artifacts.ledgerEntry()
        assertEquals("task_de72b0dfa2a248f19161:write", entry?.entryId)
        assertEquals("餐饮", entry?.category)
        // occurred_at 与 note 要跟着走，否则「买了什么」和交易时间都丢了
        assertEquals("2026-10-06 14:32:07", entry?.toReceiptFields()?.datetime)
        assertEquals("商品说明：拿铁 大杯", entry?.toReceiptFields()?.notes)

        // 退路：没有 entry_id 时按「有 amount + currency」认出抽取字段
        assertEquals(3850L, artifacts.receiptFields()?.money()?.amountMinor)
    }

    @Test
    fun `没有产物的快照不会编出一条空凭证`() {
        // LedgerEntry 的字段几乎全可空，若只按"能解码"判定，空对象也会被当成一条凭证。
        // 所以判别键必须是 entry_id。
        assertNull(obj("{}").ledgerEntry())
        assertNull(obj("""{"main": {}}""").ledgerEntry())
        assertNull(obj("""{"write": {"category": "餐饮"}}""").ledgerEntry())
        assertNull((null as JsonObject?).ledgerEntry())
    }

    @Test
    fun `失败时上游节点已完成的产物仍能被认出来`() {
        // 契约明写「已完成节点的产物保留并如实上报」。实测里 extract 抽对了字段，
        // 下游 normalize 超时导致整单失败——这时仍要能读到 extract 的产物。
        val artifacts = obj(
            """
            {"extract": {"amount": 38.5, "currency": "CNY", "merchant": "星巴克咖啡（国贸店）",
                         "direction": "expense", "confidence": 0.98},
             "error":   {"code": "upstream_llm_error"}}
            """,
        )

        assertNull(artifacts.ledgerEntry())
        val partial = artifacts.receiptFields()
        assertEquals(3850L, partial?.money()?.amountMinor)
        assertEquals("星巴克咖啡（国贸店）", partial?.merchant)
    }

    // ---------- 快照 → 终局 ----------

    private fun snapshot(raw: String): TaskSnapshot =
        json.decodeFromJsonElement(TaskSnapshot.serializer(), obj(raw))

    @Test
    fun `任务失败时已完成节点的产物必须带出来`() {
        // 实测情形：extract 抽对了字段，下游 normalize 超时导致整单失败。
        // 契约明写「已完成节点的产物保留并如实上报」，服务端也确实保留
        // （runner.py 收集所有产出过输出的节点，与任务成败无关）。
        // 客户端在这里丢掉产物 = 让用户白拍一张，这条就是防这个的。
        val failed = snapshot(
            """
            {"task_id": "task_x", "user_id": "local", "status": "failed", "source": "api",
             "mode": "async", "progress": 1.0,
             "created_at": "2026-10-06T14:00:00Z", "updated_at": "2026-10-06T14:01:00Z",
             "artifacts": {"extract": {"amount": 38.5, "currency": "CNY",
                                       "merchant": "星巴克咖啡（国贸店）", "direction": "expense",
                                       "confidence": 0.98}},
             "error": {"type": "about:blank", "title": "节点 normalize 失败", "status": 200,
                       "code": "upstream_llm_error", "retryable": true,
                       "detail": "请求超时（8.0s，档位 standard）"}}
            """,
        )

        val outcome = CaptureOutcome.fromSnapshot("task_x", failed, null)

        val f = outcome as CaptureOutcome.Failed
        assertEquals(3850L, f.partialFields?.money()?.amountMinor)
        assertEquals("星巴克咖啡（国贸店）", f.partialFields?.merchant)
        assertNotNull("失败必须给出可读的原因", f.problem)
    }

    @Test
    fun `整单失败且没有任何节点产出时产物就是空的`() {
        // 实测：文本任务在 dedupe_check / write_entry 这类节点上因输入引用解析不了而失败，
        // artifacts 是 **{}**——那时一个节点都没产出过，没有产物可保。
        // 把它和上一条分开，是为了让「有产物必须保留」这条断言仍然可证伪：
        // 若哪天客户端把非空 artifacts 吞了，上一条会红，而不是被这一条掩盖。
        val failed = snapshot(
            """
            {"task_id": "task_y", "user_id": "local", "status": "failed", "source": "api",
             "mode": "async", "progress": 1.0,
             "created_at": "2026-10-06T14:00:00Z", "updated_at": "2026-10-06T14:01:00Z",
             "artifacts": {},
             "error": {"type": "about:blank", "title": "handler error", "status": 200,
                       "code": "handler_error", "retryable": false,
                       "detail": "节点 dedupe_check 失败：节点输入引用无法解析：'envelope.text'"}}
            """,
        )

        val f = CaptureOutcome.fromSnapshot("task_y", failed, null) as CaptureOutcome.Failed
        assertNull(f.partialFields)
        assertNotNull(f.problem)
    }

    @Test
    fun `快照取不到时不算成功也不算澄清`() {
        val f = CaptureOutcome.fromSnapshot("task_z", null, null) as CaptureOutcome.Failed
        assertNotNull(f.problem)
        assertTrue("快照取不到多半是网络问题，应当可重试", f.problem!!.retryable)
    }

    @Test
    fun `任务成功但产物为空时不假装成功`() {
        val succeeded = snapshot(
            """
            {"task_id": "task_w", "user_id": "local", "status": "succeeded", "source": "api",
             "mode": "async", "progress": 1.0,
             "created_at": "2026-10-06T14:00:00Z", "updated_at": "2026-10-06T14:01:00Z",
             "artifacts": {}}
            """,
        )

        val f = CaptureOutcome.fromSnapshot("task_w", succeeded, null) as CaptureOutcome.Failed
        assertEquals("missing_artifacts", f.problem?.code)
    }

    @Test
    fun `等待澄清时优先给澄清而不是失败`() {
        val awaiting = snapshot(
            """
            {"task_id": "task_c", "user_id": "local", "status": "awaiting_clarification",
             "source": "api", "mode": "async", "progress": 0.5,
             "created_at": "2026-10-06T14:00:00Z", "updated_at": "2026-10-06T14:01:00Z",
             "clarification": {"question_id": "q_1",
                               "question": "请补充金额和支付方式", "blocking": true,
                               "options": []}}
            """,
        )

        val c = CaptureOutcome.fromSnapshot("task_c", awaiting, null) as CaptureOutcome.NeedsClarification
        assertEquals("请补充金额和支付方式", c.clarification.question)
        // 实测服务端会下发空 options，那时只能走自由文本——渲染器必须知道这一点
        assertTrue(c.clarification.options.isEmpty())
    }

    // ---------- 事件载荷 ----------

    @Test
    fun `从事件载荷里取指定节点的产出`() {
        val payload = obj(
            """
            {"subtask_id": "extract", "attempt": 1, "tier": "standard",
             "output": {"amount": 38.5, "currency": "CNY", "direction": "expense"}}
            """,
        )

        assertEquals(3850L, payload.subtaskOutput("extract")?.let {
            json.decodeFromJsonElement(ReceiptFields.serializer(), it).money()?.amountMinor
        })
        // 节点名对不上就不认，不能拿别的节点的产出凑
        assertNull(payload.subtaskOutput("normalize"))
        assertNull((null as JsonObject?).subtaskOutput("extract"))
    }
}
