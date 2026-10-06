package dev.dzsun.bookkeeping.core.statement

import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.ledger.JournalDraft
import dev.dzsun.bookkeeping.core.ledger.PostingDraft
import dev.dzsun.bookkeeping.core.money.Money
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-d 退款冲减里**不碰数据库**的那几段：从状态文本认出退款金额、照原凭证反向。
 *
 * 这两段都是纯函数，也正是最会出错的地方——认错金额、反向写歪，**都不会报错**，
 * 只会让这个月的支出少一截或多一截，而且数字看着还挺合理。
 * 涉及 Room 的那部分（幂等、事务）在 `tools/verify-migration.py` 与
 * `StatementImporter` 的注释里说明了不变量，那部分要真库才能测。
 */
class RefundReversalTest {

    /**
     * 刻意读**真实的** assets 配置，不另造一份——退款词表正是这一版新加的，
     * 另造一份的话配置写错了测试照样绿，而那正是最容易写错的地方。
     */
    private val formats: List<StatementFormat> = run {
        val file = File("src/main/assets/statement_formats.json")
        assertTrue("找不到格式配置：${file.absolutePath}", file.isFile)
        Json { ignoreUnknownKeys = true }.decodeFromString<StatementFormatFile>(file.readText()).formats
    }

    private val wechat = formats.first { it.id == "wechat" }

    private fun charge(minor: Long = 3850L, currency: String = "CNY") =
        Money.of(minor, currency)

    // ------------------------------------------------------------ 退款金额

    @Test
    fun `全额退款的标记命中时退的就是原金额`() {
        // 微信实测的状态取值。平台自己下的「全额」判断比我们解析数字更权威
        val refunded = RefundAmount.resolve("已全额退款", wechat, charge())

        assertEquals(3850L, refunded?.amountMinor)
    }

    @Test
    fun `带金额的退款状态能认出退了多少`() {
        // 实测取值「已退款¥6.60」——符号在前
        assertEquals(660L, RefundAmount.resolve("已退款¥6.60", wechat, charge())?.amountMinor)
        // 单位在后的写法也要认（部分银行的摘要这么写）
        assertEquals(660L, RefundAmount.resolve("退款 6.60 元", wechat, charge())?.amountMinor)
    }

    @Test
    fun `认不出金额时不猜成全额`() {
        // 这条最要紧：猜成全额会让这个月凭空少一整笔支出，而用户无从察觉
        assertNull(RefundAmount.resolve("已退款", wechat, charge()))
        assertNull(RefundAmount.resolve("退款处理中", wechat, charge()))
        assertNull(RefundAmount.resolve(null, wechat, charge()))
        assertNull(RefundAmount.resolve("  ", wechat, charge()))
    }

    @Test
    fun `裸数字不算金额`() {
        // 状态文本里的裸数字可能是单号片段或期数，认错了就是一笔凭空冒出来的退款
        assertNull(RefundAmount.resolve("已退款 1234567890", wechat, charge()))
        assertNull(RefundAmount.resolve("2026年10月退款", wechat, charge()))
    }

    @Test
    fun `退回来的钱不可能超过原消费`() {
        assertNull(RefundAmount.resolve("已退款¥99.00", wechat, charge(3850L)))
        assertNull(RefundAmount.resolve("已退款¥0.00", wechat, charge(3850L)))
        // 恰好等于原金额是合法的（部分退款的上界）
        assertEquals(3850L, RefundAmount.resolve("已退款¥38.50", wechat, charge())?.amountMinor)
    }

    @Test
    fun `没配退款词表时一律不认`() {
        val bank = wechat.copy(refundMarkers = emptyList(), fullRefundMarkers = emptyList())
        // 无词表 = 这家账单没有可用的退款判据，宁可不动它
        assertNull(RefundAmount.resolve("已全额退款", bank, charge()))
    }

    @Test
    fun `是不是部分退款，认不出来时返回不知道`() {
        assertEquals(false, RefundAmount.isPartial(charge(), charge()))
        assertEquals(true, RefundAmount.isPartial(charge(660L), charge()))
        // null 不是「部分」，是「不知道」——两者的处理完全不同
        assertNull(RefundAmount.isPartial(null, charge()))
    }

    // ------------------------------------------------------------ 反向凭证

    private fun expenseDraft() = JournalDraft.expense(
        dateEpochDay = 20_000,
        amount = Money.parse("38.50", "CNY"),
        fromAccountId = "asset.wechat",
        categoryAccountId = "expense.food",
        payee = "星巴克咖啡",
        source = JournalSource.STATEMENT,
    )

    @Test
    fun `全额冲减把每条分录原样取反，且仍然平`() {
        val original = expenseDraft()
        val reversal = JournalDraft.reversalOf(original, reversesJournalId = "j_1")

        assertEquals(
            original.postings.map { -it.amount.amountMinor },
            reversal.postings.map { it.amount.amountMinor },
        )
        // 复式不变式由 JournalDraft 的构造守住，这里确认反向之后依然成立
        assertEquals(0L, reversal.postings.sumOf { it.amount.amountMinor })
        assertEquals("j_1", reversal.reversesJournalId)
        assertTrue(reversal.isReversal)
        // 账户与分类都取自原凭证——重新归类会让两个分类同时错
        assertEquals(
            original.postings.map { it.accountId },
            reversal.postings.map { it.accountId },
        )
    }

    @Test
    fun `部分退款按原分录各自的符号反向，金额取退款额`() {
        val reversal = JournalDraft.reversalOf(
            expenseDraft(),
            amount = Money.parse("6.60", "CNY"),
        )

        assertEquals(0L, reversal.postings.sumOf { it.amount.amountMinor })
        // 原凭证是 [资产 -38.50, 支出分类 +38.50]，退 6.60 就是 [资产 +6.60, 支出分类 -6.60]：
        // 钱回到资产上，那笔支出在统计里被减掉 6.60
        assertEquals(
            listOf(660L, -660L),
            reversal.postings.map { it.amount.amountMinor },
        )
    }

    @Test
    fun `部分退款拒绝三条分录的拆分凭证，而不是按比例猜`() {
        val split = JournalDraft(
            dateEpochDay = 20_000,
            payee = "超市",
            note = null,
            source = JournalSource.STATEMENT,
            postings = listOf(
                PostingDraft("asset.wechat", Money.parse("-100.00", "CNY")),
                PostingDraft("expense.food", Money.parse("60.00", "CNY")),
                PostingDraft("expense.daily", Money.parse("40.00", "CNY")),
            ),
        )

        // 全额冲减不挑条数（原样取反即可）
        assertEquals(3, JournalDraft.reversalOf(split).postings.size)
        // 部分冲减要按比例摊分，规则未定——宁可拒绝也不猜
        val failure = assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.reversalOf(split, amount = Money.parse("10.00", "CNY"))
        }
        assertTrue(failure.message!!.contains("两条分录"))
    }

    @Test
    fun `退款额超过原凭证、币种不符、金额非正都被拒绝`() {
        val original = expenseDraft()
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.reversalOf(original, amount = Money.parse("99.00", "CNY"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.reversalOf(original, amount = Money.parse("1.00", "USD"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.reversalOf(original, amount = Money.parse("-1.00", "CNY"))
        }
    }

    @Test
    fun `冲减的日期默认取原凭证那天，好让它在同一个月里抵消`() {
        val original = expenseDraft()
        // 流水里「同一行状态变成已退款」不带退款时间，用它自己的消费日期才对得上
        assertEquals(original.dateEpochDay, JournalDraft.reversalOf(original).dateEpochDay)
        // 独立一行的退款有自己的日期，那时以那一行为准
        assertEquals(
            21_000L,
            JournalDraft.reversalOf(original, dateEpochDay = 21_000L).dateEpochDay,
        )
    }

    // ------------------------------------------------------------ 行的退款判定

    private fun parseTwoRows(statusExtra: String): ParseResult {
        val csv = """
            微信支付账单明细
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号
            2026-09-15 08:12:33,商户消费,星巴克咖啡,拿铁 大杯,支出,¥38.50,零钱,$statusExtra,4200000000000000000000000001,SBK1
            2026-09-16 12:30:00,商户消费,便利店,退款专营店,支出,¥12.00,零钱,支付成功,4200000000000000000000000002,SBK2
        """.trimIndent()
        return CsvStatementParser(formats).parse(csv.toByteArray())
    }

    @Test
    fun `状态里出现退款标记才算退款`() {
        val rows = parseTwoRows("已全额退款").consumptionRows
        assertTrue("状态命中就该标成退款", rows.first { it.externalRef.endsWith("01") }.isRefund)
    }

    @Test
    fun `商品说明里的退款二字不算退款`() {
        // 第二行的商品说明是「退款专营店」——那是商户名的一部分。
        // 拿自由文本去对会把正常消费认成退款，后果是静默少记一笔支出
        val rows = parseTwoRows("支付成功").consumptionRows
        assertFalse("第二行不是退款", rows.first { it.externalRef.endsWith("02") }.isRefund)
        assertFalse("第一行也不是", rows.first { it.externalRef.endsWith("01") }.isRefund)
    }

    @Test
    fun `交易类型里的退款也算，金额负号不算`() {
        // 退款标记写在**交易类型那一列**（支付宝叫「交易分类」，银行叫「摘要」）。
        // 表头带尾逗号，数据行必须同列数——少一列会被当成说明行静默跳过
        val csv = """
            支付宝交易明细查询
            账号：[syn***@example.com]
            ----支付宝支付科技有限公司  电子客户回单----
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,
            2026-09-15 08:12:33,退款,商户A,对方,退货,支出,-38.50,卡,交易成功,B1,MB1,,
            2026-09-16 08:12:33,日用百货,超市,对方,日用品,支出,-20.00,卡,交易成功,B2,MB2,,
        """.trimIndent()
        val rows = CsvStatementParser(formats).parse(csv.toByteArray()).consumptionRows

        assertTrue("交易类型写了退款", rows.first { it.externalRef == "B1" }.isRefund)
        // 负号**不是**判据：银行流水里普通借方也常写成 -38.50，
        // 靠符号判会把正常支出认成退款
        assertFalse("金额带负号的普通消费不是退款", rows.first { it.externalRef == "B2" }.isRefund)
        // 方向仍以收/支列为准，符号照旧剥掉：金额取绝对值 38.50
        assertEquals(3850L, rows.first { it.externalRef == "B1" }.amount.amountMinor)
    }

    @Test
    fun `未配退款词表的格式不会把行标成退款`() {
        val plain = wechat.copy(refundMarkers = emptyList(), fullRefundMarkers = emptyList())
        val csv = """
            微信支付账单明细
            ----------------------微信支付账单明细列表--------------------
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号
            2026-09-15 08:12:33,商户消费,星巴克咖啡,拿铁 大杯,支出,¥38.50,零钱,已全额退款,4200000000000000000000000001,SBK1
        """.trimIndent()
        val rows = CsvStatementParser(listOf(plain)).parse(csv.toByteArray()).consumptionRows

        // 这家账单没有可用的退款判据，就一行都不标——不猜
        assertTrue(rows.none { it.isRefund })
    }

    // ------------------------------------------------------------ 计划形状

    @Test
    fun `部分退款在计划里能看出来是部分的`() {
        val row = StatementRow(
            externalSource = "wechat",
            externalRef = "4200000000000000000000000009",
            dateEpochDay = 20_000,
            amount = charge(),
            direction = StatementDirection.EXPENSE,
            merchant = "星巴克咖啡",
            description = "拿铁 大杯",
            status = "已退款¥6.60",
            rawType = "商户消费",
            rawTime = "2026-09-15 08:12:33",
            rowNumber = 5,
            isRefund = true,
        )
        val full = PlannedReversal(
            row = row,
            existingJournalId = "j_1",
            draft = JournalDraft.reversalOf(expenseDraft()),
            refundAmount = charge(),
            categoryName = "餐饮",
        )
        val partial = full.copy(refundAmount = charge(660L))

        assertFalse("退满原金额不算部分", full.isPartial)
        assertTrue("退得比原消费少才算部分", partial.isPartial)
    }

    @Test
    fun `冲减凭证带着溯源 id 时才算冲减`() {
        assertFalse(JournalDraft.reversalOf(expenseDraft()).isReversal)
        assertTrue(JournalDraft.reversalOf(expenseDraft(), reversesJournalId = "j_9").isReversal)
        // 空串会让溯源查不到任何东西，构造时就该拒绝
        assertThrows(IllegalArgumentException::class.java) {
            JournalDraft.reversalOf(expenseDraft(), reversesJournalId = "")
        }
    }

    @Test
    fun `冲减用的是原凭证的分类账户，不重新归类`() {
        val original = expenseDraft()
        val reversal = JournalDraft.reversalOf(original, reversesJournalId = "j_1")
        val categoryAccount = reversal.postings.first { it.amount.isNegative() }.accountId

        assertEquals("expense.food", categoryAccount)
        // JournalStatus 默认 CLEARED：退款是已经发生的事实，不需要用户再核一遍
        assertEquals(JournalStatus.CLEARED, reversal.status)
    }
}
