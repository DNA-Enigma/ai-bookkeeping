package dev.dzsun.bookkeeping.llm

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「思考过程」的界面状态 —— **刻意独立的一份状态**。
 *
 * ## 它为什么不和正文挤在一起
 *
 * 验收里的硬要求是「整页不得随生成节奏抖动/重排」。做法只有一条：
 * **把文本存在 [MutableState] 里，让折叠区自己去读**。状态的读取发生在
 * 折叠区那个子作用域，Compose 就只会让那一个作用域失效；外层页面（输入框、
 * 卡片、导航、滚动位置、chips）拿到的是同一个对象引用，重组被整体跳过。
 *
 * 反面教材就是改造前那几处：`var liveReply by remember { mutableStateOf("") }`
 * 写在页面顶层，模型每吐一块就重组整个页面，连输入框和推荐 chips 都跟着重画。
 *
 * ## 节流
 *
 * [append] 里走 [ThinkingThrottle]：≥150ms 或每 8 个 chunk 才写一次界面状态。
 * 注意**被节流掉的内容不丢** —— 它照样进 [buffer]，只是下一次发布时一并带出去。
 */
@Stable
class ThinkingUiState(
    private val throttle: ThinkingThrottle = ThinkingThrottle(),
) {

    private val buffer = StringBuilder()

    /** 已发布的思考/生成原文。**只有折叠区读它**，别在页面顶层读。 */
    var text by mutableStateOf("")
        private set

    /** 生成是否已结束（结束后摘要换成「查看思考过程」）。 */
    var finished by mutableStateOf(false)
        private set

    /** 本轮起点（`SystemClock.elapsedRealtime()`）。null = 还没开始。 */
    var startedAtMs by mutableStateOf<Long?>(null)
        private set

    /** 展开态。默认**收起**——过程不该抢正文的位置。 */
    var expanded by mutableStateOf(false)
        private set

    /** 发布次数。单测用它断言「60 个 chunk 不会刷 60 次」，界面不读。 */
    var publishCount by mutableIntStateOf(0)
        private set

    /** 开始新一轮：清空内容、收起、重新计时。 */
    fun start(nowMs: Long) {
        buffer.setLength(0)
        text = ""
        finished = false
        expanded = false
        startedAtMs = nowMs
        publishCount = 0
        throttle.reset()
    }

    /**
     * 收到一段模型产出（思考段原文或未解析的正文都算）。
     *
     * @return 是否真的刷新了界面 —— 单测据此断言节流生效。
     */
    fun append(delta: String, nowMs: Long): Boolean {
        if (delta.isEmpty()) return false
        buffer.append(delta)
        if (!throttle.onChunk(nowMs)) return false
        publish()
        return true
    }

    /** 本轮结束（成功、失败、被取消都算）。**强制发布**，最后几个字不许丢。 */
    fun finish(nowMs: Long) {
        throttle.flush(nowMs)
        publish()
        finished = true
    }

    fun toggleExpanded() {
        expanded = !expanded
    }

    /** 用户手动收起（例如生成完了想让它安静下来）。 */
    fun collapse() {
        expanded = false
    }

    private fun publish() {
        text = buffer.toString()
        publishCount++
    }

    /** 本轮实际产出（**不经过节流**，收尾判定用）。 */
    fun bufferedText(): String = buffer.toString()
}

/**
 * 摘要一行的文案。纯函数，单测逐字钉。
 *
 *  - 进行中且收起：`思考中 12s · 已生成 84 字 ▾`（默认态）
 *  - 进行中且展开：`思考中 12s · 已生成 84 字`
 *  - 已结束：`查看思考过程`（内容已在，但不再喊「思考中」）
 *  - 一个字都没产出：`思考中 12s ▾` —— **不写「已生成 0 字」**，
 *    那看着像模型卡住了；此刻它确实只是还没吐字。
 */
fun thinkingSummary(textLength: Int, elapsedMs: Long?, finished: Boolean): String {
    if (finished) return THINKING_DONE_TITLE
    val head = "思考中" + (elapsedMs?.let { " ${compactElapsed(it)}" } ?: "")
    val body = if (textLength > 0) "已生成 $textLength 字" else ""
    return listOf(head, body).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** 完成后折叠态的那一行。 */
const val THINKING_DONE_TITLE = "查看思考过程"

/**
 * 紧凑时长：`12s` / `1m12s` / `1h02m`。
 *
 * 与 [formatElapsed] 分开是因为这里的场合是**摘要行里的一小段**，
 * 「12 秒」在 `思考中 12 秒 · 已生成 84 字 ▾` 里明显比 `思考中 12s` 更挤。
 */
fun compactElapsed(ms: Long): String {
    val sec = (ms.coerceAtLeast(0L)) / 1000
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m${(sec % 60).toString().padStart(2, '0')}s"
        else -> "${sec / 3600}h${((sec % 3600) / 60).toString().padStart(2, '0')}m"
    }
}

/**
 * 可折叠的「思考过程」区。**问账页 / 对话页 / 悬浮面板 / 设置页生成框四处共用这一套**，
 * 交互因此一致：默认收起一行摘要，点开是**局部滚动区**，再点收起。
 *
 * ## 三条防抖口径（改这个组件前先读完）
 *
 * 1. **内容只在 [ThinkingSection] 内部读**（`state.text` / `state.startedAtMs`），
 *    所以更新只让这个组件重组；调用方页面不参与。参数传的是 [ThinkingUiState]
 *    这个稳定对象，**不是字符串** —— 传字符串会把整段文本塞进参数链，
 *    父级每刷一次就跟着重组，正是要避免的那件事。
 * 2. **展开区是局部滚动**（固定最大高度 + `verticalScroll`），不参与整页布局；
 *    内容再长也只在自己框里滚。
 * 3. **高度变化走 [AnimatedVisibility] 动画**，不瞬间跳变把下面的内容顶走。
 */
@Composable
fun ThinkingSection(
    state: ThinkingUiState,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxHeight: androidx.compose.ui.unit.Dp = 160.dp,
) {
    val style = LocalTextStyle.current
    Column(modifier = modifier.fillMaxWidth()) {
        ThinkingHeader(state, style, color)
        AnimatedVisibility(
            visible = state.expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
            ) {
                Text(
                    state.text,
                    style = style,
                    color = color.copy(alpha = 0.78f),
                )
            }
        }
    }
}

/**
 * 摘要行 + 箭头。**读状态只在这里**（除展开区本身）。
 *
 * 计时只在「还没结束」时走：结束后摘要固定是「查看思考过程」，
 * 再每秒心跳重组一次纯属浪费。
 */
@Composable
private fun ThinkingHeader(
    state: ThinkingUiState,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
) {
    val running = !state.finished
    val elapsed = rememberElapsedMs(if (running) state.startedAtMs else null)
    val arrow by animateFloatAsState(
        targetValue = if (state.expanded) 180f else 0f,
        label = "thinkingArrow",
    )
    // 展开箭头 ▾ 用旋转表达，避免为了两个方向引两套图标资源
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { state.toggleExpanded() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            thinkingSummary(state.text.length, if (running) elapsed else null, state.finished),
            style = style,
            fontWeight = FontWeight.Medium,
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "▾",
            style = style,
            color = color.copy(alpha = 0.7f),
            modifier = Modifier.rotate(arrow),
        )
    }
}
