package dev.dzsun.bookkeeping.llm

import com.arm.aichat.InferenceEngine

/**
 * 等待本机模型时给用户看的**阶段**。
 *
 * 三条硬口径（对应「禁止造假进度」）：
 *  1. 每个取值都必须能从**真实状态**导出：[phaseOf] 只认引擎状态机，
 *     [UiPhase.RELEASING] 只认安装器上报的字节数，[UiPhase.DISPATCH] 只认「确实发给了调度层」。
 *  2. **没有分母的阶段不许画百分比** —— 只有 [UiPhase.RELEASING] 拿得到
 *     「已释放 / 共多少字节」，其余一律「阶段名 + 已用时 + 预计」。
 *  3. 计时一律用 `SystemClock.elapsedRealtime()`（见 `ModelProgressView.rememberElapsedMs`），
 *     不用 `currentTimeMillis`：跨天、改系统时间会让已用时往回跳。
 */
enum class UiPhase {
    /** 把内置 GGUF 释放到应用目录 —— 唯一有确定分母的阶段。 */
    RELEASING,

    /** 引擎初始化（还没开始读权重）。 */
    STARTING,

    /** 读 1 GB 权重文件。 */
    LOADING_WEIGHTS,

    /** 解码系统提示：首次冷启动最慢的一步。 */
    DECODING_PROMPT,

    /** 逐字生成中。 */
    GENERATING,

    /** 卸载权重。 */
    UNLOADING,

    /** 跑基准测试。 */
    BENCHMARKING,

    /** 发给调度层判定（网络往返，不是本机模型）。 */
    DISPATCH,

    /** 当前没有可展示的进行中阶段（未加载 / 就绪 / 报错由调用方另行渲染）。 */
    IDLE,
}

/**
 * 引擎状态 → [UiPhase]。**纯函数**，阶段的真值只在这里推导一次，各页面不许自己另写一套。
 *
 * 映射刻意细到「加载权重」和「解码系统提示」两档分开：实测这两段合计 267 秒，
 * 合成一个「加载中」等于让用户盯 4 分钟同一句话，既看不出卡在哪，也不知道还要多久。
 */
fun phaseOf(state: InferenceEngine.State): UiPhase = when (state) {
    InferenceEngine.State.Initializing -> UiPhase.STARTING
    InferenceEngine.State.LoadingModel -> UiPhase.LOADING_WEIGHTS
    InferenceEngine.State.ProcessingSystemPrompt -> UiPhase.DECODING_PROMPT
    InferenceEngine.State.Generating,
    InferenceEngine.State.ProcessingUserPrompt,
    -> UiPhase.GENERATING

    InferenceEngine.State.UnloadingModel -> UiPhase.UNLOADING
    InferenceEngine.State.Benchmarking -> UiPhase.BENCHMARKING

    // Uninitialized / Initialized / ModelReady / Error：不在「进行中的等待」里，
    // 界面各自显示未加载 / 已加载 / 失败原因，不拿一个假阶段糊上去。
    else -> UiPhase.IDLE
}

/** 阶段名。 */
fun phaseLabel(phase: UiPhase): String = when (phase) {
    UiPhase.RELEASING -> "释放内置模型"
    UiPhase.STARTING -> "启动推理引擎"
    UiPhase.LOADING_WEIGHTS -> "加载模型权重"
    UiPhase.DECODING_PROMPT -> "解码系统提示"
    UiPhase.GENERATING -> "生成回答"
    UiPhase.UNLOADING -> "卸载模型"
    UiPhase.BENCHMARKING -> "跑基准测试"
    UiPhase.DISPATCH -> "AI 服务识别中"
    UiPhase.IDLE -> "准备中"
}

/**
 * 该阶段的**预期时长**提示 —— 来自真机/模拟器实测，不是拍脑袋的数字。
 * 返回空串表示没什么可提示的（调用方就不渲染这一行）。
 */
fun phaseHint(phase: UiPhase): String = when (phase) {
    UiPhase.RELEASING -> "首次约 26 秒，写入应用目录后不用再释放"
    UiPhase.STARTING -> "很快，几秒"
    UiPhase.LOADING_WEIGHTS -> "首次约 1~4 分钟（读 1 GB 文件），可先去别的页面"
    UiPhase.DECODING_PROMPT -> "首次较久，实测约 1~5 分钟"
    UiPhase.GENERATING -> "本机逐字生成，实测单轮 2~3 分钟"
    UiPhase.UNLOADING -> "几秒"
    UiPhase.BENCHMARKING -> "几十秒"
    UiPhase.DISPATCH -> "网络往返，通常几秒；连不上会改用本机规则"

    // 引擎状态还没跟上（刚点下去 / 别的页面在加载）时会落到 IDLE ——
    // 此刻最该告诉用户的仍是「可以先去干别的」，而不是一句没有预期的「准备中」。
    UiPhase.IDLE -> FIRST_WAIT_HINT
}

/**
 * 首次等待的统一提示 —— **面板、对话页、设置页共用这一句**，别各写一份。
 * 「可以先去干别的」是这句的重点：用户最需要知道的不是还要多久，是能不能走开。
 */
const val FIRST_WAIT_HINT = "首次加载约 4 分钟，可先点别的地方，完成后本页自动更新"

/**
 * 已用时长。「秒」是用户能核对的单位（他看得见秒表），
 * 超过一分钟给「分 + 秒」，超过一小时给「小时 + 分」——不给小数，避免伪精度。
 */
fun formatElapsed(ms: Long): String {
    val sec = (ms.coerceAtLeast(0L)) / 1000
    return when {
        sec < 60 -> "$sec 秒"
        sec < 3600 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
    }
}

/** 等待主行：`<阶段名> · 已用 <时长>`。没有起点就只给阶段名（不编一个 0 秒出来）。 */
fun waitHeadline(phase: UiPhase, elapsedMs: Long?): String =
    if (elapsedMs == null) phaseLabel(phase) else "${phaseLabel(phase)} · 已用 ${formatElapsed(elapsedMs)}"

/** 释放进度的百分比；**没有分母返回 null** —— 调用方据此决定画不画进度条。 */
fun releasePercent(bytesDone: Long, bytesTotal: Long): Int? {
    if (bytesTotal <= 0L || bytesDone < 0L) return null
    return ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

/** 释放进度行：`已释放 X / 共 Y MB · Z%`，后面跟已用时。 */
fun releaseLine(bytesDone: Long, bytesTotal: Long, elapsedMs: Long?): String {
    val doneMb = bytesDone.coerceAtLeast(0L) / 1024 / 1024
    val totalMb = bytesTotal.coerceAtLeast(0L) / 1024 / 1024
    val head = "已释放 $doneMb / 共 $totalMb MB"
    val pct = releasePercent(bytesDone, bytesTotal)?.let { " · $it%" }.orEmpty()
    val tail = elapsedMs?.let { " · 已用 ${formatElapsed(it)}" }.orEmpty()
    return head + pct + tail
}

/**
 * 失败文案的两段：**具体原因 + 建议动作**（「重试」是按钮，由界面渲染，不进文案）。
 *
 * [reason] 已经是「是什么」（`issueMessage` / `UserFacingErrors` 产出），
 * [suggestion] 可空 —— 有些原因本身已含下一步（如「点卸载后重新加载」）。
 */
fun failureText(reason: String, suggestion: String? = null): List<String> =
    listOfNotNull(reason.trim().takeIf { it.isNotEmpty() }, suggestion?.trim()?.takeIf { it.isNotEmpty() })

/** 首次加载类阶段（这几档只在真的加载时出现，给「可以先走开」的提示是诚实的）。 */
val UiPhase.isFirstLoadWait: Boolean
    get() = this == UiPhase.RELEASING ||
        this == UiPhase.STARTING ||
        this == UiPhase.LOADING_WEIGHTS ||
        this == UiPhase.DECODING_PROMPT
