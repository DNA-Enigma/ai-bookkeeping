package dev.dzsun.bookkeeping.llm

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一的「等待本机模型」可视化。**五个等待面共用这一套文案与版式**：
 * 设置页端侧模型区、AI 悬浮面板、对话页、问账页、记一笔解析。
 *
 * 文案口径（见 [ModelProgress.kt]）：
 *  - 进行中 = **阶段 + 已用时 + 预期提示**；有字节分母的阶段（释放资产）给**真进度条**；
 *  - 失败 = **具体原因 + 建议动作 + 重试入口**；
 *  - 没有分母的阶段**不画百分比**，也不显示进度条。
 *
 * 配色跟随宿主：颜色取 [LocalContentColor]、字号取 [LocalTextStyle]，
 * 所以 Art 主题（设置页/面板/对话页）和 Material3 主题（问账页/记一笔）里都能直接嵌。
 */

/**
 * 已用时长的秒级心跳。起点用 [SystemClock.elapsedRealtime]（单调，跨天/改系统时间不跳）。
 *
 * @param startedAtMs 等待起点；null 表示还没开始计时（返回 0，界面就不显示假的秒数）。
 */
@Composable
fun rememberElapsedMs(startedAtMs: Long?): Long {
    var elapsed by remember(startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs == null) {
            elapsed = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsed = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    return elapsed
}

/**
 * 等待起点。发起方（点了「加载模型」/「发送」的那一页）传自己记下的 [explicitStartMs]；
 * 传 null 说明这一页**不是发起方**（比如模型在面板里被触发加载，用户才切进设置页）——
 * 那就从本页第一次看见这个等待开始计时，只增不减，离开等待态归零。
 *
 * 两种来路都是真实时间，不存在「页面一重组计时就回跳」。
 */
@Composable
fun rememberWaitStart(explicitStartMs: Long?, active: Boolean): Long? {
    var observedAt by remember { mutableStateOf<Long?>(null) }
    if (active) {
        if (observedAt == null) observedAt = SystemClock.elapsedRealtime()
    } else if (observedAt != null) {
        observedAt = null
    }
    return explicitStartMs ?: observedAt
}

/** 订阅引擎状态并折成 [UiPhase] —— 页面只管用，阶段推导统一走 [phaseOf]。 */
@Composable
fun rememberUiPhase(engineState: StateFlow<InferenceEngine.State>): UiPhase {
    val state by engineState.collectAsStateWithLifecycle()
    return phaseOf(state)
}

/**
 * 紧凑进度行（头部/气泡里用）：`<阶段> · 已用 <时长>` + 预期提示。
 * 首次加载类阶段额外带上 [FIRST_WAIT_HINT]（可先去干别的）。
 */
@Composable
fun ModelProgressLine(
    phase: UiPhase,
    startedAtMs: Long?,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    showHint: Boolean = true,
    color: Color = LocalContentColor.current,
) {
    val elapsed = rememberElapsedMs(startedAtMs)
    val style = LocalTextStyle.current
    val headline = buildString {
        if (!prefix.isNullOrEmpty()) append(prefix).append(" ")
        append(waitHeadline(phase, if (startedAtMs == null) null else elapsed))
    }
    val hint = when {
        !showHint -> ""
        phase.isFirstLoadWait -> "$FIRST_WAIT_HINT（${phaseHint(phase)}）"
        else -> phaseHint(phase)
    }
    Column(modifier = modifier) {
        Text(headline, style = style, color = color)
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(hint, style = style, color = color.copy(alpha = 0.72f))
        }
    }
}

/**
 * 完整进度块（设置页 / 问账页 / 记一笔用）：
 * 有分母给真进度条，没分母给「阶段 + 已用时 + 预期」，失败给「原因 + 动作 + 重试」。
 *
 * @param bytesDone / [bytesTotal] 仅 [UiPhase.RELEASING] 有意义；[bytesTotal] <= 0 时**不画条**
 *   （没有分母就不许画百分比——内置资产缺失时正是这种情况）。
 * @param streamingText 生成中的流式正文，非空就边生成边显示。
 * @param reason 失败的具体原因；非空则渲染失败块，忽略其余参数。
 * @param suggestion 建议动作，可空（有些原因本身已含下一步）。
 * @param onRetry 重试入口；null 不渲染按钮。
 */
@Composable
fun ModelProgressView(
    phase: UiPhase,
    startedAtMs: Long?,
    modifier: Modifier = Modifier,
    bytesDone: Long = -1L,
    bytesTotal: Long = -1L,
    streamingText: String? = null,
    reason: String? = null,
    suggestion: String? = null,
    onRetry: (() -> Unit)? = null,
    color: Color = LocalContentColor.current,
) {
    if (reason != null) {
        ModelFailureView(
            reason = reason,
            suggestion = suggestion,
            onRetry = onRetry,
            modifier = modifier,
            color = color,
        )
        return
    }

    val elapsed = rememberElapsedMs(startedAtMs)
    val style = LocalTextStyle.current
    val text = streamingText.orEmpty()

    Column(modifier = modifier) {
        val percent = if (phase == UiPhase.RELEASING) releasePercent(bytesDone, bytesTotal) else null
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp)),
                color = color,
                trackColor = color.copy(alpha = 0.18f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                releaseLine(bytesDone, bytesTotal, startedAtMs?.let { elapsed }),
                style = style,
                color = color,
            )
        } else {
            Text(
                waitHeadline(phase, startedAtMs?.let { elapsed }),
                style = style,
                color = color,
            )
        }

        val hint = when {
            phase.isFirstLoadWait -> "$FIRST_WAIT_HINT（${phaseHint(phase)}）"
            else -> phaseHint(phase)
        }
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(hint, style = style, color = color.copy(alpha = 0.72f))
        }

        if (text.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(text, style = style, color = color)
        }
    }
}

/**
 * 失败块：**具体原因 + 建议动作 + 重试入口**。
 * 三样缺一样，用户就只能对着「失败了」反复重试。
 */
@Composable
fun ModelFailureView(
    reason: String,
    modifier: Modifier = Modifier,
    suggestion: String? = null,
    onRetry: (() -> Unit)? = null,
    color: Color = LocalContentColor.current,
) {
    val style = LocalTextStyle.current
    Column(modifier = modifier) {
        failureText(reason, suggestion).forEachIndexed { i, line ->
            if (i > 0) Spacer(Modifier.height(3.dp))
            Text(line, style = style, color = color)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "重试",
                    style = style,
                    color = color,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
