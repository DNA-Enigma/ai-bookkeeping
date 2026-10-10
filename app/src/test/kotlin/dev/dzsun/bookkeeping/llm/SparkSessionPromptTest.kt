package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示 B 段（对话模式）的分流判据。
 *
 * 背景：对话模式下含金额的报账输入 6/6 次没输出数组（同一句走记账解析模式 5/5 全对）——
 * 能力在，是判据写得太薄。这里钉死**正反例都得在**：报账 → 数组、查账 → `查账`、闲聊 → reply。
 */
class SparkSessionPromptTest {

    private val prompt = buildSystemPrompt(
        expense = listOf("餐饮", "交通", "其他支出"),
        income = listOf("工资", "其他收入"),
    )

    @Test
    fun `B 段有报账的正例——含金额的句子要输出数组`() {
        assertTrue(prompt.contains("【对话】"))
        assertTrue("判据里要说清金额", prompt.contains("具体金额"))
        assertTrue("判据里要说清买了什么", prompt.contains("买了什么"))
        assertTrue("报账例子要在", prompt.contains("「午饭花了 35」"))
        assertTrue(
            "报账例子要给出数组形态",
            prompt.contains("[{\"kind\":\"expense\",\"amount\":35,\"category\":\"餐饮\""),
        )
    }

    @Test
    fun `B 段有查账的反例——问统计只回查账`() {
        assertTrue("查账例子要在", prompt.contains("「这个月花了多少」"))
        assertTrue("反例要覆盖「哪类最多」", prompt.contains("「哪类最多」"))
        assertTrue("查账信号的形态要写死", prompt.contains("{\"reply\":\"查账\"}"))
    }

    @Test
    fun `B 段有闲聊的例子——问候要给自然的 reply`() {
        assertTrue("闲聊例子要在", prompt.contains("「你好」"))
        assertTrue("闲聊输出形态是 reply", prompt.contains("{\"reply\":\"你好，我是阿账"))
    }

    @Test
    fun `三组例子互不相同——不能只给一组再让模型自己推广`() {
        assertTrue(prompt.contains("报账："))
        assertTrue(prompt.contains("查账："))
        assertTrue(prompt.contains("说话："))
    }

    @Test
    fun `科目表仍进提示词——分类是数据不是代码`() {
        assertTrue(prompt.contains("支出：餐饮、交通、其他支出"))
        assertTrue(prompt.contains("收入：工资、其他收入"))
        assertTrue(prompt.contains("不得自造"))
    }

    @Test
    fun `同一份提示词两次生成必须逐字一致——不然会触发模型重载`() {
        val again = buildSystemPrompt(listOf("餐饮", "交通", "其他支出"), listOf("工资", "其他收入"))
        assertTrue(prompt == again)
    }
}
