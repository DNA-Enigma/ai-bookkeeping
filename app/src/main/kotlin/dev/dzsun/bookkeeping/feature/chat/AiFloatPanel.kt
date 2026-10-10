package dev.dzsun.bookkeeping.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import dev.dzsun.bookkeeping.core.designsystem.Art
import dev.dzsun.bookkeeping.core.designsystem.SealBadge
import dev.dzsun.bookkeeping.feature.entry.AddEntryViewModel
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class PanelStatus { PREPARING, STREAMING, DONE, FAILED }

/**
 * AI 悬浮面板：点首页右下角的 AI 球**原地展开**，不跳页。
 *
 * 与对话页/问账页共用同一个本地模型单例与同一份系统提示，
 * 差别只在呈现：这里**边生成边上屏**（[AzhangStream.Text]），用户看到的是 AI 在打字，
 * 而不是对着空屏等整段生成完。
 *
 * 三个能力：
 *  1. 快速问答（流式）
 *  2. 快速记账：模型认出这是一笔账 → 出卡片 → 点「记下来」直接入账
 *  3. 兜底去处：答不了就给「去问账页 / 去对话页」两个入口，不让用户卡在面板里
 */
@Composable
fun AiFloatPanel(
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onAskClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Art.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val azhang = remember {
        EntryPointAccessors.fromApplication(context, ChatAiEntryPoint::class.java).azhangChat()
    }
    val addEntry: AddEntryViewModel = hiltViewModel()

    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(PanelStatus.DONE) }
    var entry by remember { mutableStateOf<dev.dzsun.bookkeeping.feature.entry.ParsedEntry?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 上一次真正发出去的那句 —— 失败态的「重试」重发它，而不是让用户重新打一遍。 */
    var lastText by remember { mutableStateOf("") }

    // 打开面板就预热模型：1GB 模型 + 系统提示解码要花时间，
    // 等用户打完字再开始加载，那条消息就白等了。
    LaunchedEffect(Unit) { azhang.warmUp() }

    fun startTurn(text: String) {
        if (text.isEmpty() || busy) return
        lastText = text
        busy = true
        saved = null
        entry = null
        reply = ""
        status = PanelStatus.PREPARING
        scope.launch {
            var finish: AzhangTurn? = null
            val ok = withTimeoutOrNull(STREAM_BUDGET_MS) {
                azhang.streamTurn(text).collect { ev ->
                    when (ev) {
                        is AzhangStream.Preparing -> status = PanelStatus.PREPARING
                        is AzhangStream.Text -> {
                            status = PanelStatus.STREAMING
                            reply += ev.delta
                        }
                        is AzhangStream.Finish -> finish = ev.turn
                    }
                }
                true
            } == true

            // finish 在闭包里被赋值过，Kotlin 不允许对它做智能转换 ——
            // 先拷成本地不可变引用，后面就能直接 is 判断了。
            val result = finish
            when {
                // 超时：把已经吐出来的部分留着，别整段吞掉
                !ok -> {
                    status = if (reply.isNotEmpty()) PanelStatus.DONE else PanelStatus.FAILED
                    if (reply.isEmpty()) reply = TIMEOUT_TEXT
                }

                result is AzhangTurn.Entry -> {
                    entry = result.entries.first()
                    status = PanelStatus.DONE
                }

                result is AzhangTurn.Reply -> {
                    // 模型认领「查账」→ 不编数字，换成能直接照做的引导
                    reply = if (result.isLedgerQuery) QUERY_GUIDE else result.body
                    // 模型只回了话、由本地规则补出来的卡片，照样出 —— 见 withRulesEntryFallback
                    result.entries.firstOrNull()?.let { entry = it }
                    status = PanelStatus.DONE
                }

                else -> {
                    // 失败必须显示**真实原因**（模型没就绪/超时/输出不是 JSON），
                    // 不能再挂一句与现场无关的兜底话术；原因本身已带下一步动作。
                    status = PanelStatus.FAILED
                    val reason = (result as? AzhangTurn.Unavailable)
                        ?.reason?.takeIf { it.isNotBlank() } ?: FAILED_TEXT
                    reply = if (reply.isEmpty()) reason else "$reply\n\n—— $reason"
                }
            }
            busy = false
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        startTurn(text)
    }

    Column(
        modifier = modifier
            .width(340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        /* 头部：身份 + 状态 + 关闭 */
        Row(verticalAlignment = Alignment.CenterVertically) {
            SealBadge("账", size = 26.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "阿账",
                    style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                    color = p.ink,
                )
                Text(
                    when (status) {
                        PanelStatus.PREPARING -> "正在准备本机模型…"
                        PanelStatus.STREAMING -> "回答生成中"
                        PanelStatus.DONE -> if (entry != null) "识别到账目，确认后可入账" else "本机模型 · 离线可用"
                        PanelStatus.FAILED -> "这次没答上"
                    },
                    style = TextStyle(fontSize = 10.5.sp, letterSpacing = .5.sp),
                    color = when (status) {
                        PanelStatus.FAILED -> p.warn
                        PanelStatus.PREPARING, PanelStatus.STREAMING -> p.accent
                        else -> p.ink3
                    },
                )
            }
            Text(
                "收起",
                style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.sp),
                color = p.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        /* 认出是账 → 记账卡片 */
        val e = entry
        if (e != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(p.radiusL))
                    .background(p.bg)
                    .border(1.dp, p.accent, RoundedCornerShape(p.radiusL))
                    .padding(12.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "记一笔",
                            style = TextStyle(fontSize = 11.sp, letterSpacing = 2.sp),
                            color = p.ink3,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (saved != null) "已入账" else e.categoryName,
                            style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.sp),
                            color = if (saved != null) p.pos else p.accent,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "¥${e.amountText}",
                            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                            color = p.ink,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            e.note,
                            style = TextStyle(fontSize = 12.5.sp),
                            color = p.ink3,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (saved == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(p.accent)
                                    .clickable(enabled = !busy) {
                                        addEntry.saveQuickEntry(
                                            amountText = e.amountText,
                                            categoryName = e.categoryName,
                                            note = e.note,
                                            dateEpochDay = LocalDate.now().toEpochDay(),
                                        ) { ok -> saved = if (ok) "ok" else "fail" }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp),
                            ) {
                                Text("记下来", style = TextStyle(fontSize = 12.sp), color = p.bg)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .border(1.dp, p.line, RoundedCornerShape(999.dp))
                                    .clickable { entry = null }
                                    .padding(horizontal = 16.dp, vertical = 7.dp),
                            ) {
                                Text("算了", style = TextStyle(fontSize = 12.sp), color = p.ink2)
                            }
                        }
                        if (saved == "fail") {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "没记上（分类或账户没准备好），去对话页里再试一次。",
                                style = TextStyle(fontSize = 11.5.sp),
                                color = p.warn,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        /* 回答区：流式正文 */
        if (reply.isNotEmpty() || status == PanelStatus.PREPARING) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(p.radiusL))
                    .background(p.bg)
                    .padding(12.dp),
            ) {
                Text(
                    text = reply.ifEmpty { "…" },
                    style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
                    color = p.ink,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        /* 输入行 */
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(fontSize = 13.5.sp, color = p.ink),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(p.bg)
                    .border(1.dp, p.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text("说一句，比如「打车 28」", style = TextStyle(fontSize = 13.sp), color = p.ink3)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(9.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (busy) p.line2 else p.accent)
                    .clickable(enabled = !busy, onClick = ::send)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    if (busy) "…" else "发送",
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = if (busy) p.ink3 else p.bg,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        /* 去处：失败时把第一个 chip 换成「重试」（重发上一条），否则给两个兜底入口 */
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == PanelStatus.FAILED && lastText.isNotEmpty()) {
                PanelChip(
                    "重试",
                    Modifier.weight(1f),
                    onClick = { startTurn(lastText) },
                )
            } else {
                PanelChip("去问账页", Modifier.weight(1f), onClick = onAskClick)
            }
            PanelChip("去对话页", Modifier.weight(1f), onClick = onOpenChat)
        }
    }
}

@Composable
private fun PanelChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = Art.colors
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, p.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 11.5.sp, letterSpacing = .5.sp), color = p.ink2)
    }
}

/** 模型认领「查账」时给的引导 —— 它不编数字，所以要给一句能直接照做的话。 */
private const val QUERY_GUIDE =
    "这个得看你的真实账本，我不编数字。\n点下面「去问账页」问：「这个月餐饮花了多少」。"

/** 兜底失败文案 —— 仅当连具体原因都没拿到（如 turn 为空）时才用，通常显示的是 Unavailable.reason。 */
private const val FAILED_TEXT =
    "这次没答上来。\n可以点「重试」再发一次，或点下面的入口去对话页。"

/** 整轮超过预算、且一个字都没吐出来时的原因文案。 */
private const val TIMEOUT_TEXT =
    "这次生成超时了（超过 90 秒没出结果）。\n可以点「重试」再发一次，或点下面的入口去对话页。"

/** 面板是即时场景：首次含模型加载的整体预算。 */
private const val STREAM_BUDGET_MS = 90_000L
