package dev.dzsun.bookkeeping.llm

import dev.dzsun.bookkeeping.feature.ask.MODEL_GEN_TIMEOUT_MS
import dev.dzsun.bookkeeping.feature.chat.AzhangChat
import dev.dzsun.bookkeeping.feature.chat.PANEL_TURN_BUDGET_MS
import dev.dzsun.bookkeeping.feature.chat.PREPARING_TIMEOUT_TEXT
import dev.dzsun.bookkeeping.feature.chat.TIMEOUT_TEXT
import dev.dzsun.bookkeeping.feature.entry.SparkAiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三处入口（面板 / 对话页 / 问账页）必须引用**同一份**超时预算。
 *
 * 背景：此前面板 90s、对话 45s、问账 20s 三套写死的数字互相打架，
 * 而模拟器实测冷启动系统提示解码 97~100s、单轮生成 16~46s ——
 * 问账必超时、面板首条必失败。预算只能有一个定义处：[SparkSession]。
 */
class TimeoutBudgetTest {

    @Test
    fun `初始化预算 240s——与记账解析链路同值`() {
        assertEquals(240_000L, SparkSession.INIT_BUDGET_MS)
        assertEquals(
            "两条链路的初始化预算必须一致，否则同一个模型在不同页面有不同的就绪口径",
            SparkAiParser.INIT_TIMEOUT_MS,
            SparkSession.INIT_BUDGET_MS,
        )
    }

    @Test
    fun `生成预算 90s——覆盖实测最慢的一倍有余`() {
        assertEquals(90_000L, SparkSession.GEN_BUDGET_MS)
        // 实测单轮生成最慢 46s（软测 TC-21/TC-08），预算必须盖得住再留余量
        assertTrue(SparkSession.GEN_BUDGET_MS > 46_000L)
    }

    @Test
    fun `对话页引用的是同一份预算而不是自己写死的`() {
        assertEquals(SparkSession.INIT_BUDGET_MS, AzhangChat.INIT_TIMEOUT_MS)
        assertEquals(SparkSession.GEN_BUDGET_MS, AzhangChat.GEN_TIMEOUT_MS)
        // 改前是 180_000 / 45_000 —— 与 240_000 / 90_000 对不上就是又写死了一份
        assertTrue(AzhangChat.INIT_TIMEOUT_MS >= 240_000L)
        assertTrue(AzhangChat.GEN_TIMEOUT_MS >= 90_000L)
    }

    @Test
    fun `问账页引用的是同一份生成预算——20s 那个写死值不许回来`() {
        assertEquals(SparkSession.GEN_BUDGET_MS, MODEL_GEN_TIMEOUT_MS)
        assertTrue(MODEL_GEN_TIMEOUT_MS >= 90_000L)
    }

    @Test
    fun `面板预算是初始化加生成两段之和`() {
        assertEquals(
            SparkSession.INIT_BUDGET_MS + SparkSession.GEN_BUDGET_MS,
            PANEL_TURN_BUDGET_MS,
        )
        assertEquals(330_000L, PANEL_TURN_BUDGET_MS)
    }

    @Test
    fun `超时文案要区分「还在准备」和「真出不来」`() {
        assertTrue("准备中那句要说清首次耗时", PREPARING_TIMEOUT_TEXT.contains("首次约 1~2 分钟"))
        assertTrue("准备中那句要给出下一步", PREPARING_TIMEOUT_TEXT.contains("重试"))
        assertTrue("生成超时那句的秒数取自统一预算", TIMEOUT_TEXT.contains("${SparkSession.GEN_BUDGET_MS / 1000} 秒"))
        assertTrue("两句话不能一样", TIMEOUT_TEXT != PREPARING_TIMEOUT_TEXT)
    }
}
