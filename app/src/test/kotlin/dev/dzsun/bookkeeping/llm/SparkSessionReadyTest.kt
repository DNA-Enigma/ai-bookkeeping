package dev.dzsun.bookkeeping.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 [ensureReady] 的两块纯逻辑：[ReadyResult] 的判别形状，和 [issueMessage] 的分支文案。
 *
 * 引擎本身不碰 —— 加载失败要能区分「没模型 / 加载抛错 / 状态机拒绝」，
 * 靠的就是这两个类型，所以在这里钉死，而不是等真机上出问题再看日志。
 */
class SparkSessionReadyTest {

    @Test
    fun `Ready 分支可判别且不带 issue`() {
        val result: ReadyResult = ReadyResult.Ready
        assertTrue(result is ReadyResult.Ready)
        assertFalse(result is ReadyResult.Failed)
    }

    @Test
    fun `Failed 分支携带原始 issue 不被吞掉`() {
        val result: ReadyResult = ReadyResult.Failed(ModelIssue.LoadFailed("File not found"))
        assertTrue(result is ReadyResult.Failed)
        assertFalse(result is ReadyResult.Ready)
        assertEquals(ModelIssue.LoadFailed("File not found"), (result as ReadyResult.Failed).issue)
    }

    @Test
    fun `NoModelFile 文案告诉用户去哪儿加载`() {
        val msg = issueMessage(ModelIssue.NoModelFile)
        assertTrue(msg.contains("未找到模型文件"))
        assertTrue(msg.contains("加载模型"))
    }

    @Test
    fun `LoadFailed 保留原始报错并给出重试动作`() {
        val msg = issueMessage(ModelIssue.LoadFailed("Unsupported model architecture"))
        assertTrue(msg.contains("Unsupported model architecture"))
        assertTrue(msg.contains("卸载"))
    }

    @Test
    fun `Interrupted 带上引擎状态名作为现场证据`() {
        val msg = issueMessage(ModelIssue.Interrupted("Generating"))
        assertTrue(msg.contains("Generating"))
        assertTrue(msg.contains("状态异常"))
        assertTrue(msg.contains("卸载"))
    }

    @Test
    fun `三类 issue 的文案互不相同`() {
        val noFile = issueMessage(ModelIssue.NoModelFile)
        val loadFailed = issueMessage(ModelIssue.LoadFailed("x"))
        val interrupted = issueMessage(ModelIssue.Interrupted("Error"))
        assertEquals(3, setOf(noFile, loadFailed, interrupted).size)
    }
}
