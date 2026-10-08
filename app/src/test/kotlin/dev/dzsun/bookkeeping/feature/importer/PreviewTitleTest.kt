package dev.dzsun.bookkeeping.feature.importer

import dev.dzsun.bookkeeping.core.statement.StatementDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览行标题怎么取。
 *
 * 真机实测：支付宝把个人收钱码的名字**截断**了——`交易对方="飞"`，
 * 而真正有用的是 `商品说明="收钱码收款"`。旧逻辑「商户非空就占位」会把描述挤没，
 * 用户只看到一个孤零零的「飞」，无从判断这是哪笔钱。
 */
class PreviewTitleTest {

    private fun row(
        merchant: String?,
        description: String?,
        rowNumber: Int = 7,
    ) = PreviewRow(
        kind = PreviewRow.Kind.IMPORT,
        rowNumber = rowNumber,
        dateEpochDay = 20_000,
        direction = StatementDirection.EXPENSE,
        amountMinor = 38_50,
        merchant = merchant,
        description = description,
        categoryName = "餐饮",
        categoryFromHistory = true,
        skipReason = null,
    )

    @Test
    fun `商户够长时做标题_描述做副标题补充`() {
        val t = previewTitle(row("星巴克咖啡", "拿铁 大杯"))
        assertEquals("星巴克咖啡", t.primary)
        assertEquals("拿铁 大杯", t.secondary)
    }

    @Test
    fun `商户够长时描述不能被吃掉`() {
        // 这正是 bug 本身：描述是「买了什么」的来源，不该因为商户非空就消失
        val t = previewTitle(row("腊汁肉夹馍", "经营码交易"))
        assertEquals("腊汁肉夹馍", t.primary)
        assertEquals("经营码交易", t.secondary)
    }

    @Test
    fun `商户被截断成两个字时用描述做标题`() {
        val t = previewTitle(row("飞", "收钱码收款"))
        assertEquals("收钱码收款", t.primary)
        assertEquals("飞", t.secondary)
    }

    @Test
    fun `商户被截断成一个字时也用描述做标题`() {
        val t = previewTitle(row("李", "滴滴出行"))
        assertEquals("滴滴出行", t.primary)
    }

    @Test
    fun `商户与描述相同时不重复显示`() {
        val t = previewTitle(row("星巴克咖啡", "星巴克咖啡"))
        assertEquals("星巴克咖啡", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `没有商户时用描述做标题`() {
        val t = previewTitle(row(null, "拿铁 大杯"))
        assertEquals("拿铁 大杯", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `商户够长但没有描述时副标题为空`() {
        val t = previewTitle(row("星巴克咖啡", null))
        assertEquals("星巴克咖啡", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `商户和描述都没有时回落到行号`() {
        val t = previewTitle(row(null, null, rowNumber = 12))
        assertEquals("第 12 行", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `商户只有空白时当作没有商户`() {
        val t = previewTitle(row("   ", "拿铁 大杯"))
        assertEquals("拿铁 大杯", t.primary)
    }
}
