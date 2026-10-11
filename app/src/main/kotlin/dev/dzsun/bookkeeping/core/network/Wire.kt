package dev.dzsun.bookkeeping.core.network

import kotlinx.serialization.json.Json

/**
 * 线上格式的共用底座：JSON 口径与错误体解码。
 *
 * `/v1`（任务、媒体、事件流）与 `/auth`（账号）是**同一个服务端的两套端点**，
 * 编解码口径必须逐字一致：容忍新增字段、不发 null、错误体一律还原成有类型的
 * [Problem]。各写一份的话，两边的 `ignoreUnknownKeys` 迟早会漂，而漂掉的那一侧
 * 会在服务端加字段的那天开始崩——契约承诺只做加法，客户端就得真的做到
 * 「服务端只做加法也不崩」。
 */
internal val wireJson = Json {
    // 契约承诺只做加法（新增可选字段），而手机无法强制升级 —— 必须容忍不认识的字段
    ignoreUnknownKeys = true
    // 不把 null 发出去，但**默认值要发**：
    // supports_last_event_id 这类字段若被省略，服务端会按它自己的默认处理，我们就失去了声明能力
    explicitNulls = false
    encodeDefaults = true
}

internal const val CONTENT_TYPE_JSON = "application/json"

/** 非契约错误体（HTML 错误页、网关返回）最多带这么多字符进异常，避免把整页塞进内存。 */
private const val MAX_DETAIL_CHARS = 500

/**
 * 把错误响应还原成有类型的 [Problem]。
 *
 * 响应体不是合法 Problem 时**不假装它是传输失败**——那会让调用方
 * 对一个 400 盲目重试。这里合成一个 code 为 `untyped_error` 的 Problem，
 * 并只按 HTTP 语义决定可重试性。
 */
internal fun problemFrom(httpStatus: Int, body: ByteArray): DispatcherException {
    val decoded = runCatching { wireJson.decodeFromString<Problem>(body.decodeToString()) }.getOrNull()
    val problem = decoded ?: Problem(
        type = "about:blank",
        title = "HTTP $httpStatus",
        status = httpStatus,
        code = "untyped_error",
        retryable = httpStatus >= 500,
        detail = body.decodeToString().take(MAX_DETAIL_CHARS).ifBlank { null },
    )
    return DispatcherException(problem, problem.detail ?: problem.title)
}
