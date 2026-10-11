package dev.dzsun.bookkeeping.core.network

import java.io.IOException

/**
 * 用户可见的错误文案白名单。
 *
 * 界面上**只允许**出现这里的话。服务端 `detail`、主机名、路由名、上游错误体
 * 一律不进界面——排查信息留在异常的 `cause` / `message` 里给开发者看，
 * 不进字符串给用户看。本项目主源集刻意不写 `Log`（release 未开 minify，
 * `Log` 会原样进包），所以原始信息只活在异常对象里，不做二次落盘。
 *
 * 用法：界面侧拿到 [Throwable] 后一律过 [userMessageFor] 或直接取这里的常量，
 * **不要**自己拼 `e.message` / `problem.detail`。
 */
object UserFacingErrors {

    /** 连不上服务（传输层失败、超时、DNS）。 */
    const val UNREACHABLE = "连不上识别服务，请稍后再试"

    /** 问账那条链路：听不懂 / 拿不到可执行的结果。 */
    const val ASK = "这句话暂时没听懂，换个问法试试"

    /** 识别 / 解析失败。 */
    const val CAPTURE = "识别失败，换种说法试试"

    /** 归类拿不到建议。界面侧已带重试入口，文案保持中性。 */
    const val CLASSIFY = "这次没能归类，稍后再试"

    /** 版本更新检查 / 下载失败。 */
    const val UPDATE = "网络不通，稍后再试"

    /** 保存 / 入账失败。 */
    const val SAVE = "没保存成功，请再试一次"

    /** 通用兜底。 */
    const val GENERIC = "出了点小问题，请稍后再试"

    // ------------------------------------------------------------ 账号

    /**
     * 验证码或密码不对。
     *
     * 账号这条路上，服务端的 `detail` 是**专门写给人看的**（"验证码已过期，请重新获取"
     * 这类），但本项目口径是服务端文案一律不进界面，所以这里按结构化字段重写：
     * `code` + `retry_after_ms` 能判的都用它们判，判不出来的合并成一句。
     *
     * 已知的代价：服务端区分了"没发过码 / 已过期 / 就是输错了"三种，而 `code`
     * 三者都是 `unauthorized`（`context.remaining_attempts` 也没被序列化进响应体，
     * 见报告），所以这里恢复不出那个粒度。**补偿手段在客户端**：发送成功时拿到
     * 了 `expires_in`，"验证码已过期"这个最常见的分支由本地倒计时自己算得出来，
     * 不必去猜服务端那句话。
     */
    const val AUTH_CODE_OR_PASSWORD = "验证码或密码不正确，请检查后重试"

    /** 本地就能判定的输入问题；服务端的 `invalid_request` 也落到这里。 */
    const val AUTH_INPUT = "输入有误，请检查后重试"

    /** 邮箱形状不对。本地先判一道，服务端也会judge（宽松正则，只挡明显不是邮箱的）。 */
    const val AUTH_EMAIL_INVALID = "邮箱格式不正确"

    /** 密码长度。服务端要求 ≥6，本地先判以免白跑一趟。 */
    const val AUTH_PASSWORD_TOO_SHORT = "密码至少 6 位"

    /**
     * 发送太频繁。[seconds] 取自 `Problem.retryAfterMs`（**结构化字段**，不是
     * 从服务端那句话里抠数字）。拿不到就退回不带秒数的说法。
     */
    fun authTooOften(seconds: Int?): String =
        if (seconds != null && seconds > 0) "发送太频繁，请 $seconds 秒后再试" else "发送太频繁，请稍后再试"

    /**
     * 账号链路：异常 → 用户文案。
     *
     * 只看 `code`，不读 `detail`——理由有两条，第二条更硬：一是本项目口径；
     * 二是实测服务端的 `invalid_request` 会把**原始输入回显**进 detail
     * （"邮箱格式不正确：'bad'"），那是一段由用户控制的文本，不该出现在
     * 我们渲染的句子中间。
     */
    fun authMessageFor(e: Throwable): String = when {
        e !is DispatcherException -> GENERIC
        e.problem == null -> UNREACHABLE
        e.code == Problem.CODE_RATE_LIMITED -> authTooOften((e.problem.retryAfterMs?.let { it / 1000 } ?: 60L).toInt())
        e.code == Problem.CODE_UNAUTHORIZED -> AUTH_CODE_OR_PASSWORD
        e.code == Problem.CODE_INVALID_REQUEST -> AUTH_INPUT
        // 服务端没配 SMTP 时是 policy_violation。对用户而言"发不出信"就是"稍后再试"，
        // 说"策略违规"只会让他以为是自己做错了什么。
        e.code == Problem.CODE_POLICY_VIOLATION -> UNREACHABLE
        e.code == Problem.CODE_HANDLER_ERROR -> UNREACHABLE
        else -> GENERIC
    }

    /**
     * 异常 → 用户文案。**不透传** `e.message`：它可能带着 URL、主机名或上游 JSON。
     *
     * 判据只有「连不连得上」这一件事——用户能采取的行动只有重试，
     * 再细的分类他无能为力。细节留在异常对象里。
     */
    fun userMessageFor(e: Throwable?): String = when {
        e == null -> GENERIC
        e is DispatcherException && e.problem == null -> UNREACHABLE
        e is IOException -> UNREACHABLE
        e is DispatcherException -> CAPTURE
        else -> GENERIC
    }

    /** 问账链路专用：任何失败都回同一句话，不区分原因。 */
    fun askMessageFor(e: Throwable?): String = ASK
}
