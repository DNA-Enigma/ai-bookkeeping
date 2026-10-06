package dev.dzsun.bookkeeping.feature.entry

import dev.dzsun.bookkeeping.core.network.ClarificationAnswer
import dev.dzsun.bookkeeping.core.network.DispatcherClient
import dev.dzsun.bookkeeping.core.network.TaskFeedback

/**
 * 有调度层时的澄清出口：把 `edits[]` 按契约报回 [DispatcherClient]。
 * 没有 [taskId] 的纯本地流程退化为 no-op，不假装上报成功。
 */
class DispatcherClarificationPort(
    private val client: DispatcherClient,
) : ClarificationPort {

    override suspend fun answer(taskId: String?, answer: ClarificationAnswer) {
        if (taskId == null) return
        client.clarify(taskId, answer)
    }

    override suspend fun feedback(taskId: String?, feedback: TaskFeedback) {
        if (taskId == null) return
        client.feedback(taskId, feedback)
    }
}
