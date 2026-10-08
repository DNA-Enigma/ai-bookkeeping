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
 *
 * 而「商户被截断」那一版的规则也不够：`收钱码收款` **同样是句废话**。
 * 两列都没信息时，唯一还有信息量的是账单自带的「交易分类」列。
 */
class PreviewTitleTest {

    private fun row(
        merchant: String?,
        description: String?,
        rowNumber: Int = 7,
        rawType: String? = null,
    ) = PreviewRow(
        kind = PreviewRow.Kind.IMPORT,
        rowNumber = rowNumber,
        dateEpochDay = 20_000,
        direction = StatementDirection.EXPENSE,
        amountMinor = 38_50,
        merchant = merchant,
        description = description,
        rawType = rawType,
        categoryName = "餐饮",
        categoryFromHistory = true,
        skipReason = null,
    )

    // —— 两列都没信息量：退回账单自带的「交易分类」 ——
    // 样本取自 2026-10-06 导出的支付宝账单（538 笔支出里命中这 12 笔）

    @Test
    fun `商户截断且描述是收钱码话术时_用交易分类当标题`() {
        // 实测：交易对方="飞" | 商品说明="收钱码收款" | 交易分类="餐饮美食" | 9.50
        val t = previewTitle(row("飞", "收钱码收款", rawType = "餐饮美食"))
        assertEquals("餐饮美食", t.primary)
        // 「飞」和「收钱码收款」正是不该再露面的那两个词
        assertNull(t.secondary)
    }

    @Test
    fun `商户截断且描述是经营码话术时_用交易分类当标题`() {
        // 实测：交易对方="烤肠" | 商品说明="经营码交易" | 交易分类="家居家装" | 3.00
        val t = previewTitle(row("烤肠", "经营码交易", rawType = "家居家装"))
        assertEquals("家居家装", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `两个字的名字也算没有信息量`() {
        // 实测「菜店」出现 5 笔，单看这两个字说不出是买什么的
        val t = previewTitle(row("菜店", "收钱码收款", rawType = "商业服务"))
        assertEquals("商业服务", t.primary)
    }

    @Test
    fun `没有商户且描述是话术时_也用交易分类`() {
        val t = previewTitle(row(null, "移动支付", rawType = "交通出行"))
        assertEquals("交通出行", t.primary)
    }

    @Test
    fun `商户和描述都是空的_照样用交易分类而不是行号`() {
        // 「第 5 行」是三档里最没用的一档，账单给了分类就该用它
        val t = previewTitle(row(null, null, rowNumber = 5, rawType = "日用百货"))
        assertEquals("日用百货", t.primary)
    }

    @Test
    fun `账单没给交易分类时_退回原来那两档规则`() {
        // rawType 为空说明账单自己也没说——这时编不出更好的名字，宁可显示截断的商户
        val t = previewTitle(row("飞", "收钱码收款", rawType = null))
        assertEquals("收钱码收款", t.primary)
        assertEquals("飞", t.secondary)
    }

    // —— 反例：商户有信息量时，一个字都不许动 ——

    @Test
    fun `商户有信息量时描述是话术也不动标题`() {
        // 实测这类有 140 笔，最容易误伤——「收钱码收款」是话术，但商户名是真的
        val t = previewTitle(row("小东北麻辣烫", "收钱码收款", rawType = "餐饮美食"))
        assertEquals("小东北麻辣烫", t.primary)
        assertEquals("收钱码收款", t.secondary)
    }

    @Test
    fun `商户是哈啰出行时标题仍是商户`() {
        val t = previewTitle(row("哈啰出行", "哈啰单车卡抵扣骑行费用", rawType = "交通出行"))
        assertEquals("哈啰出行", t.primary)
    }

    @Test
    fun `商户只有两个字但描述具体时_标题是描述而不是交易分类`() {
        // ⚠️ 「美团」只有两个字，按判据属于「商户无信息」这一档，所以它**不是**
        // 「商户名有信息」那 140 笔里的。但这一行的描述是具体的（写了买了什么），
        // 两个条件只命中一个 → 修复不碰它，交易分类不许抢标题。
        //
        // 这是阈值取 2 的代价：两字品牌名会像被截断的名字一样被让位给描述。
        // 阈值抬到 3 的话，实测那 12 笔里的 11 笔（卤肉/烤肠/蔬菜/菜店/炸串）会全部漏掉。
        val description = "蜜雪冰城（碧桂园店）-美团App-26100511100300001309467273229272"
        val t = previewTitle(row("美团", description, rawType = "文化休闲"))
        assertEquals(description, t.primary)
        assertEquals("美团", t.secondary)
    }

    @Test
    fun `商户有信息量时哪怕账单给了交易分类也不能抢标题`() {
        // 交易分类是「餐饮美食」，但「星巴克咖啡」明显更好——分类只是兜底
        val t = previewTitle(row("星巴克咖啡", "拿铁 大杯", rawType = "餐饮美食"))
        assertEquals("星巴克咖啡", t.primary)
        assertEquals("拿铁 大杯", t.secondary)
    }

    // —— 原规则：描述有信息量时不能被商户挤掉 ——

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
    fun `商户被截断但描述有信息量时用描述做标题`() {
        // 描述不是话术，它比交易分类更具体（「滴滴出行」胜过「交通出行」）
        val t = previewTitle(row("李", "滴滴出行", rawType = "交通出行"))
        assertEquals("滴滴出行", t.primary)
        assertEquals("李", t.secondary)
    }

    @Test
    fun `商户与描述相同时不重复显示`() {
        val t = previewTitle(row("星巴克咖啡", "星巴克咖啡"))
        assertEquals("星巴克咖啡", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `商户够长但没有描述时副标题为空`() {
        val t = previewTitle(row("星巴克咖啡", null))
        assertEquals("星巴克咖啡", t.primary)
        assertNull(t.secondary)
    }

    @Test
    fun `商户和描述都没有且账单也没给分类时回落到行号`() {
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
