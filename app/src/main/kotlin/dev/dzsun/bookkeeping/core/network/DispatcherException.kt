package dev.dzsun.bookkeeping.core.network

/**
 * 调度层返回的结构化错误，或传输层失败。
 *
 * 契约硬性要求错误**必须是有类型的**：不得把上游异常吞成字符串再拼进正文，
 * 因为那样调用方无法判断该不该重试。所以这里保留 [problem] 原样，
 * 重试决策一律读 [retryable]，不做字符串匹配。
 *
 * [problem] 为 null 表示压根没拿到响应（连不上、超时、DNS 失败）——
 * 这类是**可重试**的，与「服务端明确说不行」不同。
 */
class DispatcherException(
    val problem: Problem?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val code: String get() = problem?.code ?: CODE_TRANSPORT

    /** 只重试契约标记为可重试的；传输失败也算可重试。 */
    val retryable: Boolean get() = problem?.retryable ?: (problem == null)

    val httpStatus: Int? get() = problem?.status

    /** 服务端在任何模型开销之前就拒了这个媒体。客户端应换格式或压缩后重试，而不是重发。 */
    val isMediaRejected: Boolean get() = code in Problem.MIME_PREFLIGHT_CODES

    /** 幂等键冲突：同一个 key 配了不同的请求体。重试没用，得换 key。 */
    val isIdempotencyConflict: Boolean get() = code == Problem.CODE_IDEMPOTENCY_CONFLICT

    override fun toString(): String =
        "DispatcherException(code=$code, http=${problem?.status}, retryable=$retryable, $message)"

    companion object {
        const val CODE_TRANSPORT = "transport_error"
    }
}
