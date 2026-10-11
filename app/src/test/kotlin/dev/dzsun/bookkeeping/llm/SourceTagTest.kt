package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源标注：问账页脚注、对话页气泡、解析卡片三处共用同一份文案。
 *
 * 这一批断言对应验收里的两条：
 *  1. 结果里必须能看见 `src=MODEL` / `src=RULES`；
 *  2. 规则兜底**必须带具体原因**——上一版那句不带原因的「没有经过 AI 服务」
 *     会让用户以为功能根本没接入。
 */
class SourceTagTest {

    @Test
    fun `模型来源的脚注含 src=MODEL`() {
        assertTrue(SourceTag.Model.footnote().contains("src=MODEL"))
        assertTrue(SourceTag.Model.isModel)
    }

    @Test
    fun `规则来源的脚注含 src=RULES 与具体原因`() {
        val tag = SourceTag.rules("未找到模型文件，去设置页点「加载模型」")
        val text = tag.footnote()
        assertTrue("必须能看出走的是规则：$text", text.contains("src=RULES"))
        assertTrue("必须带上具体原因：$text", text.contains("未找到模型文件"))
        assertFalse(tag.isModel)
    }

    @Test
    fun `规则来源不许没有原因`() {
        listOf("", "   ").forEach { blank ->
            val thrown = runCatching { SourceTag.rules(blank) }.exceptionOrNull()
            assertTrue("空原因应当构造不出来，实际：$thrown", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `模型来源不许带兜底原因`() {
        val thrown = runCatching { SourceTag(SourceKind.MODEL, reason = "顺手写的") }.exceptionOrNull()
        assertTrue("MODEL 带 reason 说明调用方不确定自己在哪条路上", thrown is IllegalArgumentException)
    }

    @Test
    fun `金额不符的机械判据代码会显示在脚注里`() {
        val tag = SourceTag.rules("模型输出不合法", code = SourceTag.CODE_AMOUNT_MISMATCH)
        assertTrue(tag.footnote().contains("MODEL_AMOUNT_MISMATCH"))
    }

    @Test
    fun `两条短标签区分得开`() {
        assertEquals("本机模型", SourceTag.Model.shortLabel)
        assertEquals("规则兜底", SourceTag.rules("模型未加载").shortLabel)
    }

    @Test
    fun `从 ModelIssue 生成规则来源时用的是同一套用户文案`() {
        val tag = SourceTag.rulesOf(ModelIssue.NoModelFile)
        assertEquals(issueMessage(ModelIssue.NoModelFile), tag.reason)
    }
}
