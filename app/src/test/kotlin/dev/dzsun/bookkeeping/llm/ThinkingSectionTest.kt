package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考区的节流与文案。
 *
 * 验收里写死的两条硬要求：
 *  - 「更新做节流：≥150ms 或每积累 N 个 chunk 刷一次，禁止每个 token 触发一次重组」；
 *  - 「默认收起只显示一行摘要（`思考中 12s · 已生成 84 字 ▾`）」。
 */
class ThinkingSectionTest {

    // —— 节流 ——

    @Test
    fun `第一块立刻可见_不让用户对着空区等`() {
        val throttle = ThinkingThrottle()
        assertTrue(throttle.onChunk(1_000L))
    }

    @Test
    fun `同一时刻连来十块_攒不满 N 块就不刷`() {
        // minChunks 调大，单独考察「时间还没到、块数也不够」时一次都不该刷
        val throttle = ThinkingThrottle(minIntervalMs = 10_000L, minChunks = 99)
        val results = (0 until 10).map { throttle.onChunk(1_000L) }
        assertEquals("只有第一块立刻可见", 1, results.count { it })
    }

    @Test
    fun `攒满 N 块即使没到 150ms 也要刷`() {
        val throttle = ThinkingThrottle(minIntervalMs = 10_000L, minChunks = 8)
        // 第一块先刷一次并重置计数，此后每 8 块再刷一次 → 第 9 块
        val results = (0 until 9).map { throttle.onChunk(1_000L) }
        assertTrue("第一块刷一次", results[0])
        assertFalse("中间不刷", results.drop(1).dropLast(1).any { it })
        assertTrue("自上次刷新起攒满 8 块必须刷（慢模型的兜底）", results[8])
    }

    @Test
    fun `每秒 60 块的真实节奏下刷新次数远小于块数`() {
        val throttle = ThinkingThrottle()
        var published = 0
        // 模拟 1 秒内 60 个 chunk（约 16ms 一个），共 3 秒
        repeat(180) { i ->
            if (throttle.onChunk(i * 16L)) published++
        }
        // 150ms 一次 → 3 秒 ≈ 20 次；给 30 的上限留足余量，但必须远小于 180
        assertTrue("刷新次数应远小于 chunk 数，实际 $published", published <= 30)
        assertTrue("也不该少到看不见进度，实际 $published", published >= 15)
    }

    @Test
    fun `时间跳变到过去也不会卡死`() {
        val throttle = ThinkingThrottle()
        throttle.onChunk(5_000L)
        // 改系统时间会让读数往回走；此时不该永远返回 false
        assertFalse(throttle.onChunk(4_000L))
        assertTrue("攒满 N 块仍然要能刷", (0 until 8).any { throttle.onChunk(4_000L) })
    }

    @Test
    fun `收尾强制发布_最后几个字不许被节流掉`() {
        val throttle = ThinkingThrottle()
        throttle.onChunk(1_000L)
        assertFalse("距上次刷新不足 150ms", throttle.onChunk(1_016L))
        assertTrue(throttle.flush(1_020L))
    }

    // —— 状态持有与发布 ——

    @Test
    fun `节流掉的内容不丢_下一次发布会一并带出去`() {
        val state = ThinkingUiState()
        state.start(0L)
        state.append("abc", 0L)      // 第一块立刻发布
        state.append("def", 10L)     // 被节流
        state.append("ghi", 20L)     // 被节流
        assertEquals("abc", state.text)
        state.finish(30L)            // 收尾强制发布
        assertEquals("abcdefghi", state.text)
    }

    @Test
    fun `六十块只发布个位数次数`() {
        val state = ThinkingUiState()
        state.start(0L)
        var published = 0
        repeat(60) { i -> if (state.append("x", i * 16L)) published++ }
        assertTrue("实际 $published 次", published <= 12)
        assertEquals("但一个字都不能少", 60, state.bufferedText().length)
    }

    @Test
    fun `默认收起_完成后仍然是收起态`() {
        val state = ThinkingUiState()
        state.start(0L)
        assertFalse(state.expanded)
        state.append("思考内容", 0L)
        state.finish(1L)
        assertFalse("完成后自动折叠，正文气泡单独出", state.expanded)
    }

    @Test
    fun `点一下展开再点一下收起`() {
        val state = ThinkingUiState()
        state.start(0L)
        state.toggleExpanded()
        assertTrue(state.expanded)
        state.toggleExpanded()
        assertFalse(state.expanded)
    }

    // —— 摘要文案 ——

    @Test
    fun `进行中的摘要就是需求里那一行`() {
        assertEquals("思考中 12s · 已生成 84 字", thinkingSummary(84, 12_000L, finished = false))
    }

    @Test
    fun `一个字都没产出时不写已生成 0 字`() {
        assertEquals("思考中 3s", thinkingSummary(0, 3_000L, finished = false))
    }

    @Test
    fun `还没有起点时只给阶段名`() {
        assertEquals("思考中", thinkingSummary(0, null, finished = false))
    }

    @Test
    fun `完成后换一行字_不再喊思考中`() {
        assertEquals(THINKING_DONE_TITLE, thinkingSummary(120, 30_000L, finished = true))
    }

    @Test
    fun `紧凑时长给秒分时三档`() {
        assertEquals("0s", compactElapsed(0L))
        assertEquals("12s", compactElapsed(12_400L))
        assertEquals("1m12s", compactElapsed(72_000L))
        assertEquals("1h02m", compactElapsed(3_720_000L))
    }
}
