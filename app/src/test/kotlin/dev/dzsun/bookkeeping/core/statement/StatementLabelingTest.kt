package dev.dzsun.bookkeeping.core.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「账单自己没说清这是干什么的」的判据。
 *
 * 样本取自 2026-10-06 导出的支付宝账单（538 笔支出）：
 * 命中 **12 笔**，另有 **140 笔**是「商户名有信息、描述是话术」——后者一笔都不能误伤。
 */
class StatementLabelingTest {

    // —— 商户名有没有信息量 ——

    @Test
    fun `一到两个字的名字算没有信息量`() {
        // 实测被截断的对方名：飞、卤肉、烤肠、蔬菜、菜店、炸串
        assertTrue(StatementLabeling.isUninformativeMerchant("飞"))
        assertTrue(StatementLabeling.isUninformativeMerchant("菜店"))
        assertTrue(StatementLabeling.isUninformativeMerchant("烤肠"))
    }

    @Test
    fun `三个字及以上的名字就算有信息量`() {
        assertFalse(StatementLabeling.isUninformativeMerchant("小东北麻辣烫"))
        assertFalse(StatementLabeling.isUninformativeMerchant("鲜爆果品"))
        assertFalse(StatementLabeling.isUninformativeMerchant("哈啰出行"))
        // 边界：三个字刚好越过阈值
        assertFalse(StatementLabeling.isUninformativeMerchant("肯德基"))
    }

    @Test
    fun `两个字的品牌名同样按没有信息量处理`() {
        // 「美团」只有两个字，落在阈值上。这条是刻意的：阈值若抬到 3，
        // 实测命中那 12 笔里的 11 笔（卤肉/烤肠/蔬菜/菜店/炸串）就全部漏掉。
        // 代价是「美团」这种两字品牌也会被让位给描述——而它的描述是具体的，
        // 两个条件只命中一个，所以那一行照旧不动（见 PreviewTitleTest）。
        assertTrue(StatementLabeling.isUninformativeMerchant("美团"))
    }

    @Test
    fun `空值与斜杠都算没有信息量`() {
        // 支付宝的不计收支行整列写「/」，那不是真的有个叫「/」的商户
        assertTrue(StatementLabeling.isUninformativeMerchant(null))
        assertTrue(StatementLabeling.isUninformativeMerchant("   "))
        assertTrue(StatementLabeling.isUninformativeMerchant("/"))
    }

    @Test
    fun `脱敏后的账号算没有信息量`() {
        // 长度够但一个字母都没有，如「173******22」
        assertTrue(StatementLabeling.isUninformativeMerchant("173******22"))
        assertTrue(StatementLabeling.isUninformativeMerchant("--------"))
    }

    @Test
    fun `带字母的脱敏邮箱仍算有信息量`() {
        // 「zij***@hellobike.com」有字母，不归到"没信息"里
        assertFalse(StatementLabeling.isUninformativeMerchant("zij***@hellobike.com"))
    }

    // —— 描述是不是平台话术 ——

    @Test
    fun `实测出现过的平台话术都能认出来`() {
        // 真实账单里的频次：经营码交易 65、收钱码收款 56、移动支付 15、订单付款 9、转账 4
        assertTrue(StatementLabeling.isUninformativeDescription("经营码交易"))
        assertTrue(StatementLabeling.isUninformativeDescription("收钱码收款"))
        assertTrue(StatementLabeling.isUninformativeDescription("移动支付"))
        assertTrue(StatementLabeling.isUninformativeDescription("订单付款"))
        assertTrue(StatementLabeling.isUninformativeDescription("转账"))
    }

    @Test
    fun `话术词尾带数字空格下划线也算`() {
        // 「支付宝支付0273」实测 7 笔——批次号缀在后面
        assertTrue(StatementLabeling.isUninformativeDescription("支付宝支付0273"))
        assertTrue(StatementLabeling.isUninformativeDescription("扫码支付 12"))
        assertTrue(StatementLabeling.isUninformativeDescription("付款_1"))
    }

    @Test
    fun `空描述算没有信息量`() {
        assertTrue(StatementLabeling.isUninformativeDescription(null))
        assertTrue(StatementLabeling.isUninformativeDescription("  "))
    }

    @Test
    fun `带信息的描述不能被话术词吞掉`() {
        // 这两条真实取值里含「充值」「付款」，但前缀才是信息所在
        assertFalse(StatementLabeling.isUninformativeDescription("长安通（互联互通） 充值"))
        assertFalse(StatementLabeling.isUninformativeDescription("在 888888 消费扫码付款"))
        assertFalse(StatementLabeling.isUninformativeDescription("哈啰单车卡抵扣骑行费用"))
        assertFalse(StatementLabeling.isUninformativeDescription("高德打车订单"))
    }

    // —— 合起来：什么时候该用「交易分类」当名字 ——

    @Test
    fun `商户无信息加描述话术才用交易分类`() {
        assertEquals(
            "餐饮美食",
            StatementLabeling.statementTypeName("飞", "收钱码收款", "餐饮美食"),
        )
    }

    @Test
    fun `商户有信息时一律不用交易分类`() {
        // ⚠️ 实测 140 笔落在这里，这是最不能误伤的一档
        assertNull(StatementLabeling.statementTypeName("小东北麻辣烫", "收钱码收款", "餐饮美食"))
        assertNull(StatementLabeling.statementTypeName("哈啰出行", "哈啰单车卡抵扣骑行费用", "交通出行"))
    }

    @Test
    fun `描述有信息时不用交易分类`() {
        // 「滴滴出行」比「交通出行」更具体，没有理由退到更粗的那一档
        assertNull(StatementLabeling.statementTypeName("李", "滴滴出行", "交通出行"))
    }

    @Test
    fun `账单没给交易分类时返回空`() {
        // 编不出更好的名字时如实返回 null，交给调用方退回原来的规则
        assertNull(StatementLabeling.statementTypeName("飞", "收钱码收款", null))
        assertNull(StatementLabeling.statementTypeName("飞", "收钱码收款", "   "))
    }

    @Test
    fun `商户写着斜杠但描述说了是哪个月的账单时不抢名字`() {
        // 「花呗主动还款-2026年10月账单」比「信用借还」具体，不该被分类顶掉
        // （这类行本身是不计收支，解析阶段就排除了，这条只管名字取得对不对）
        assertNull(
            StatementLabeling.statementTypeName("/", "花呗主动还款-2026年10月账单", "信用借还"),
        )
    }

    // —— 这行在账本里的名字：预览标题 / 摘要 / 明细共用一个口径 ——

    @Test
    fun `交易分类能顶上时就用它`() {
        assertEquals("餐饮美食", StatementLabeling.rowLabel("飞", "收钱码收款", "餐饮美食"))
    }

    @Test
    fun `用不上交易分类时退回商品说明`() {
        // 商户名有信息量（小东北麻辣烫）或描述本身有信息（哈啰单车卡…）都不该退到分类
        assertEquals("收钱码收款", StatementLabeling.rowLabel("小东北麻辣烫", "收钱码收款", "餐饮美食"))
        assertEquals(
            "哈啰单车卡抵扣骑行费用",
            StatementLabeling.rowLabel("哈啰出行", "哈啰单车卡抵扣骑行费用", "交通出行"),
        )
    }

    @Test
    fun `没有商品说明时返回空而不是空串`() {
        // 空明细行会被 JournalDraft 拒绝——「想起买了什么」用不上一个空串
        assertNull(StatementLabeling.rowLabel("星巴克咖啡", null, "商户消费"))
        assertNull(StatementLabeling.rowLabel("星巴克咖啡", "   ", "商户消费"))
    }

    @Test
    fun `商户没信息又没商品说明时_退回交易分类而不是空`() {
        // 空描述比话术更容易被漏掉：这时「交易分类」是唯一还有信息量的东西
        assertEquals("餐饮美食", StatementLabeling.rowLabel("飞", null, "餐饮美食"))
    }
}
