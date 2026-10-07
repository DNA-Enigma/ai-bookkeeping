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
