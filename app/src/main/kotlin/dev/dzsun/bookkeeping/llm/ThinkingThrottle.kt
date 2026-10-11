package dev.dzsun.bookkeeping.llm

/**
 * 「思考过程」刷新界面的**节流阀**。
 *
 * ## 它挡的是什么
 *
 * 模型一个 chunk 一个 chunk 地吐。若每来一个 chunk 就写一次界面状态，
 * 屏幕会以每秒几十次的频率重组——用户看到的是整页在抖，而不是只有思考区在长。
 * 真机实测的验收项就是「整页不得随生成节奏抖动/重排」。
 *
 * ## 判据（满足其一即可刷）
 *
 *  - 距上次刷新 **≥ [minIntervalMs]**（默认 150ms）—— 快模型也不会超过约 6.7 次/秒；
 *  - 或距上次刷新已累积 **≥ [minChunks]** 个 chunk（默认 8）—— 慢模型不会长时间一个字不动。
 *
 * ## 为什么时间是参数而不是内部取 `SystemClock`
 *
 * 纯 Kotlin、无 Android 依赖 → 单测直接喂假时钟，把「每秒 60 个 chunk 只刷 6 次」
 * 这类断言钉死。真机上调用方传 `SystemClock.elapsedRealtime()`（单调，
 * 跨天/改系统时间不跳）。
 */
class ThinkingThrottle(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minChunks: Int = DEFAULT_MIN_CHUNKS,
) {

    private var lastPublishMs: Long? = null
    private var chunksSincePublish = 0

    /**
     * 收到一个 chunk。
     *
     * @return true = 现在该把累积的内容发布给界面了。**false 时调用方仍要累积**，
     *   只是先别写界面状态——节流掉的内容不能丢，下一次发布时一并带出去。
     */
    fun onChunk(nowMs: Long): Boolean {
        chunksSincePublish++
        val last = lastPublishMs
        val due = last == null ||
            nowMs - last >= minIntervalMs ||
            chunksSincePublish >= minChunks
        if (due) {
            lastPublishMs = nowMs
            chunksSincePublish = 0
        }
        return due
    }

    /**
     * 收尾（生成结束、出错、用户切走）时强制发布剩下的内容。
     *
     * **必须调**：最后一个 chunk 之后若距上次刷新不足 150ms，它会被节流掉，
     * 于是思考区永远差最后几个字。
     */
    fun flush(nowMs: Long): Boolean {
        lastPublishMs = nowMs
        chunksSincePublish = 0
        return true
    }

    /** 开始新一轮：把节流状态清干净，第一块立刻可见。 */
    fun reset() {
        lastPublishMs = null
        chunksSincePublish = 0
    }

    companion object {
        /** 刷新间隔下限（毫秒）。 */
        const val DEFAULT_MIN_INTERVAL_MS = 150L

        /** 累积多少个 chunk 至少要刷一次（慢模型的兜底）。 */
        const val DEFAULT_MIN_CHUNKS = 8
    }
}
