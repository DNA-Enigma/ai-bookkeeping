package dev.dzsun.bookkeeping.core.ledger

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动入账的置信度判定。
 *
 * 这块判错的代价不对称：**把该问的放过去**会在用户没看的情况下记错一笔账，
 * 而账是复式的、还可能已经进了报表，事后很难发现；**把不该问的拦下来**
 * 只是多弹一次确认卡。所以每条断言都偏向保守那一侧。
 */
class ConfidenceGateTest {

    private val threshold = ConfidenceGate.DEFAULT_THRESHOLD

    // ------------------------------------------------------------ 阈值边界

    @Test
    fun `差一点到阈值要确认`() {
        // 0.84 < 0.85：边界下方，必须问
        assertTrue(ConfidenceGate.requiresConfirmation(0.84f, threshold))
        assertFalse(ConfidenceGate.canAutoPost(0.84f, threshold))
    }

    @Test
    fun `正好到阈值直接入账`() {
        // 0.85 == 0.85：**取等号算达标**。这是刻意的——阈值是用户设的
        // 「低于这个数我才要看」，写 `>` 会让用户设 0.85 时实际生效的是 0.86
        assertFalse(ConfidenceGate.requiresConfirmation(0.85f, threshold))
        assertTrue(ConfidenceGate.canAutoPost(0.85f, threshold))
    }

    @Test
    fun `超过阈值直接入账`() {
        assertFalse(ConfidenceGate.requiresConfirmation(0.86f, threshold))
        assertTrue(ConfidenceGate.canAutoPost(0.86f, threshold))
    }

    // ------------------------------------------------------------ 反例

    @Test
    fun `阈值调成 1 时全部都要确认`() {
        // 用户把阈值拉满 = 「永不自动入账」，是个合法的保守选择。
        // 服务端实测最高给到 0.98，所以这条同时盖住了「放行路径关掉之后还能走通」
        val max = ConfidenceGate.MAX_THRESHOLD
        listOf(0.0f, 0.5f, 0.85f, 0.98f, 0.999f).forEach { confidence ->
            assertTrue(
                "阈值 1.0 时 confidence=$confidence 仍应要确认",
                ConfidenceGate.requiresConfirmation(confidence, max),
            )
        }
        // 只有真给到 1.0 才放行
        assertTrue(ConfidenceGate.canAutoPost(1.0f, max))
    }

    @Test
    fun `拿不到置信度时一律要确认`() {
        // 这条是整块最要紧的。null 不是"未知但大概没事"，而是"真不知道"。
        // 它有三种真实来路：老服务端不给、走 single_tool_action 的纯文本路由
        // 产物里没有这个字段、事件流断了只回看到 LedgerEntry（那种产物不带置信度）。
        // 任何一条被当成"可信"，都会在一整条路由上静默跳过用户确认
        listOf(
            ConfidenceGate.DEFAULT_THRESHOLD,
            ConfidenceGate.MIN_THRESHOLD,
            0.0f,
        ).forEach { t ->
            assertTrue("阈值 $t 下 null 也该要确认", ConfidenceGate.requiresConfirmation(null, t))
            assertFalse(ConfidenceGate.canAutoPost(null, t))
        }
    }

    @Test
    fun `阈值调到下限时几乎都放行，但拿不到值的仍然要问`() {
        val min = ConfidenceGate.MIN_THRESHOLD
        assertTrue(ConfidenceGate.canAutoPost(0.5f, min))
        assertTrue(ConfidenceGate.canAutoPost(0.9f, min))
        assertTrue(ConfidenceGate.requiresConfirmation(0.49f, min))
        // 放得再宽也不放 null —— 放宽阈值和放弃判断是两回事
        assertTrue(ConfidenceGate.requiresConfirmation(null, min))
    }

    @Test
    fun `越界的阈值被夹进合法范围而不是报错`() {
        assertEquals(ConfidenceGate.MIN_THRESHOLD, ConfidenceGate.clamp(-1f))
        assertEquals(ConfidenceGate.MIN_THRESHOLD, ConfidenceGate.clamp(0.1f))
        assertEquals(0.85f, ConfidenceGate.clamp(0.85f))
        assertEquals(ConfidenceGate.MAX_THRESHOLD, ConfidenceGate.clamp(3f))
    }

    // ------------------------------------------------------------ 配置

    @Test
    fun `assets 里的默认阈值就是兜底值，且落在声明范围内`() {
        // 刻意读**真实的** assets 配置，而不是在测试里另造一份——
        // 另造一份的话配置写错了测试照样绿，而配置正是这块最容易写错的地方
        val file = File("src/main/assets/entry_rules.json")
        assertTrue("找不到 entry_rules.json：${file.absolutePath}", file.isFile)
        val parsed = Json { ignoreUnknownKeys = true }
            .decodeFromString<EntryRulesFile>(file.readText())

        assertEquals(ConfidenceGate.DEFAULT_THRESHOLD, parsed.confidence.autoPostThreshold)
        // 默认值必须落在自己声明的可调范围内，否则界面滑块一打开就在越界状态
        assertTrue(
            "默认值 ${parsed.confidence.autoPostThreshold} 应落在 " +
                "${parsed.confidence.min}..${parsed.confidence.max} 内",
            parsed.confidence.autoPostThreshold in parsed.confidence.min..parsed.confidence.max,
        )
        assertEquals(ConfidenceGate.MIN_THRESHOLD, parsed.confidence.min)
        assertEquals(ConfidenceGate.MAX_THRESHOLD, parsed.confidence.max)
    }

    @Test
    fun `配置读不到时兜底成保守阈值而不是崩掉`() {
        // EntryRuleCatalog.load() 是 runCatching + getOrDefault(ConfidenceRules())。
        // 这条盖的是那条兜底路径的**结果**：默认构造出来的就是 0.85 那组值。
        val fallback = ConfidenceRules()
        assertEquals(ConfidenceGate.DEFAULT_THRESHOLD, fallback.autoPostThreshold)
        assertEquals(ConfidenceGate.MIN_THRESHOLD, fallback.min)
        assertEquals(ConfidenceGate.MAX_THRESHOLD, fallback.max)
        assertTrue(ConfidenceGate.requiresConfirmation(0.84f, fallback.autoPostThreshold))
    }
}
