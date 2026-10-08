package dev.dzsun.bookkeeping.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.*
import dev.dzsun.bookkeeping.feature.ask.AskStage
import dev.dzsun.bookkeeping.feature.ask.AskViewModel
import dev.dzsun.bookkeeping.feature.entry.AddEntryViewModel
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ============================================================
 *  AI 对话页（集成版）
 *  消息路由（三段，不许互相顶替）：
 *   1. 省钱建议类   → 不编数字，引导用户问真实账目
 *   2. 含金额的话   → 本地解析卡；「确认入账」走 AddEntryViewModel.saveQuickEntry
 *                     真落库（同一条 JournalDraft 路径），失败如实说没入账
 *   3. 其他问账     → AskViewModel：调度层翻译 → 本机 Room 聚合 →
 *                     中文答案。账目不出设备，这条链路是现成的。
 * ============================================================
 */

/**
 * 对话里解析出的一张待确认卡片。
 *
 * [amountText] 是用户原样的数字串（如 "35" / "12.50"）——**刻意不用 Double**：
 * 金额在账本里一律整数最小单位，任何一处浮点都可能把 12.50 变成 12.499…。
 */
data class ParsedEntry(val category: String, val note: String, val amountText: String)

sealed class ChatMsg {
    data class Text(val fromAgent: Boolean, val body: String) : ChatMsg()
    /** [raw] 是用户原话，「修改」时回填输入框用。 */
    data class EntryCard(val entry: ParsedEntry, val raw: String) : ChatMsg()
}

@Composable
fun ChatScreen(
    onClose: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
    addEntryViewModel: AddEntryViewModel = hiltViewModel(),
) {
    val p = Art.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val askState by viewModel.state.collectAsStateWithLifecycle()

    val messages = remember { mutableStateListOf<ChatMsg>() }
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    fun scroll() {
        scope.launch {
            delay(60)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }
    }

    fun agentSay(body: String, delayMs: Long = 800) {
        scope.launch {
            typing = true; scroll()
            delay(delayMs)
            typing = false
            messages += ChatMsg.Text(true, body); scroll()
        }
    }

    // 问账流水线的回答 → 消息流（stage 每次迁移都触发）
    LaunchedEffect(askState.stage) {
        when (val s = askState.stage) {
            is AskStage.Interpreting -> { typing = true; scroll() }
            is AskStage.Answered -> {
                typing = false
                val a = s.answer
                val text = buildString {
                    append(a.headline)
                    if (a.detail.isNotEmpty()) append("\n").append(a.detail.joinToString("\n"))
                    a.footnote?.let { append("\n—— ").append(it) }
                }
                messages += ChatMsg.Text(true, text); scroll()
            }
            is AskStage.Unavailable -> {
                typing = false
                // s.reason 已是白名单文案（见 UserFacingErrors），不透传服务端原文。
                // 但光说「换个问法」没用——用户不知道换成哪种，所以附一个必然能成的例子。
                messages += ChatMsg.Text(true, askUnavailableReply(s.reason))
                scroll()
            }
            else -> {}
        }
    }

    fun entryParse(raw: String, amountText: String) {
        scope.launch {
            typing = true; scroll()
            delay(900)
            typing = false
            val cat = guessCategory(raw)
            val note = raw.replace(Regex("[，,]?\\s*\\d+(?:\\.\\d+)?\\s*"), "")
                .replace(Regex("花了|花|了"), "").trim().ifEmpty { "未备注" }
            messages += ChatMsg.EntryCard(ParsedEntry(cat, note, amountText), raw); scroll()
        }
    }

    /**
     * 对话里「确认入账」：交给 [AddEntryViewModel.saveQuickEntry] 真写库。
     * 成功、失败各说各话——**没写进去却说「记好了」是最伤信任的一种 bug**。
     */
    fun confirmEntry(card: ChatMsg.EntryCard) {
        addEntryViewModel.saveQuickEntry(
            amountText = card.entry.amountText,
            categoryName = card.entry.category,
            note = card.entry.note,
            dateEpochDay = LocalDate.now().toEpochDay(),
        ) { ok ->
            if (ok) {
                agentSay("已入账。可在首页流水中查看。\n还有别的要记吗？", 700)
            } else {
                agentSay(
                    "这笔没能入账（账户或分类还没准备好）。\n" +
                        "点中央「＋」在记一笔面板里核对后再保存。",
                    700,
                )
            }
        }
    }

    fun userSay(text: String) {
        messages += ChatMsg.Text(false, text); scroll()
        when (val intent = classifyChatInput(text)) {
            ChatIntent.Advice ->
                // 不编金额：省钱建议要真算就得看真实账目。先把用户引到能真答的问题上。
                agentSay(
                    "省钱这事得看你的真实账目，我不编数字。\n" +
                        "试着问：「这个月餐饮花了多少」「哪类花得最多」，我看完再一起想办法。",
                    900,
                )

            is ChatIntent.Record -> entryParse(text, intent.amountText)

            // 打招呼也回一句像样的，并顺手告诉他这个入口能干什么。
            is ChatIntent.SmallTalk -> agentSay(intent.reply, 600)

            is ChatIntent.Ask -> {
                // 真实问账链路：调度层 → Room → 中文答案
                viewModel.onQuestionChange(intent.question)
                viewModel.onAsk()
            }
        }
    }

    LaunchedEffect(Unit) { agentSay("下午好。想记账、查账，还是聊聊怎么省钱？说一句就行。", 500) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        /* 头部 */
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SealBadge("账", size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("阿账", style = TextStyle(fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.sp), color = p.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(p.pos))
                    Spacer(Modifier.width(6.dp))
                    Text("贴心管家 · 在线", style = TextStyle(fontSize = 11.sp, letterSpacing = 1.sp), color = p.ink3)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, p.line, RoundedCornerShape(999.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text("收起", style = TextStyle(fontSize = 12.sp, letterSpacing = 2.sp), color = p.ink2)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))

        /* 消息流 */
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(messages) { msg ->
                when (msg) {
                    is ChatMsg.Text -> TextBubble(msg.fromAgent, msg.body)
                    is ChatMsg.EntryCard -> EntryBubble(
                        entry = msg.entry,
                        onConfirm = { confirmEntry(msg) },
                        onEdit = {
                            // 「修改」= 把原话回填输入框，让用户改完重发。
                            input = msg.raw
                            scroll()
                        },
                    )
                }
            }
            if (typing) item { TypingBubble() }
        }

        /* 建议问题 */
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("午饭花了 35", "这个月餐饮花了多少", "这个月咖啡多少钱", "给我省钱建议").forEach { q ->
                ArtChip(q, selected = false, onClick = { userSay(q) })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))

        /* 输入栏 */
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                textStyle = TextStyle(fontSize = 14.5.sp, fontFamily = Art.type.body, color = p.ink),
                cursorBrush = SolidColor(p.accent),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) Text("说一句话就能记账，比如：打车 28", style = TextStyle(fontSize = 14.5.sp), color = p.ink3)
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (p.dark) p.accent else p.ink)
                    .clickable {
                        val v = input.trim()
                        if (v.isNotEmpty()) { input = ""; userSay(v) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "发送",
                    tint = if (p.dark) Color(0xFF131109) else p.bg,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/* ---------------- 气泡 ---------------- */

@Composable
private fun TextBubble(fromAgent: Boolean, body: String) {
    val p = Art.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromAgent) Arrangement.Start else Arrangement.End) {
        if (fromAgent) { SealBadge("账", size = 28.dp); Spacer(Modifier.width(10.dp)) }
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(p.radiusL))
                .background(if (fromAgent) p.card else (if (p.dark) p.accent else p.ink))
                .then(
                    if (fromAgent && Art.style != ArtStyle.MINIMAL)
                        Modifier.border(1.dp, p.line, RoundedCornerShape(p.radiusL))
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                body,
                style = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, fontFamily = Art.type.body),
                color = if (fromAgent) p.ink else (if (p.dark) Color(0xFF131109) else p.bg),
            )
        }
    }
}

@Composable
private fun EntryBubble(
    entry: ParsedEntry,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
) {
    val p = Art.colors
    // 按过任一按钮就锁住：既不能再点「确认入账」，也不能重复点「修改」。
    var done by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SealBadge("账", size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(p.radiusL))
                .background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(p.radiusL)),
        ) {
            Text("已为你解析，确认后入账：", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = TextStyle(fontSize = 14.sp, fontFamily = Art.type.body), color = p.ink)
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("¥ ", style = TextStyle(fontFamily = Art.type.num, fontSize = 17.sp), color = p.ink2)
                // 显示用户原样的数字串，不做浮点格式化成 %,.2f——那是账本里唯一的真值。
                Text(entry.amountText,
                    style = TextStyle(fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontSize = 30.sp, fontFeatureSettings = "tnum"),
                    color = p.ink)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(entry.category, entry.note, "今天").forEach { tag ->
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, p.line, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(tag, style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.sp), color = p.ink2)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(when { done -> p.pos; p.dark -> p.accent; else -> p.ink })
                        .clickable(enabled = !done) {
                            done = true
                            onConfirm()
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (done) "已提交" else "确认入账",
                        style = TextStyle(fontSize = 12.5.sp, letterSpacing = 2.sp),
                        color = if (p.dark && !done) Color(0xFF131109) else p.bg)
                }
                Box(
                    Modifier.weight(1f).clickable(enabled = !done) {
                        done = true
                        onEdit()
                    }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("修改", style = TextStyle(fontSize = 12.5.sp, letterSpacing = 2.sp), color = p.ink2)
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val p = Art.colors
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SealBadge("账", size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(p.radiusL))
                .background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(p.radiusL))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse), label = "dot$i")
                Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(p.ink3))
            }
        }
    }
}

/** 演示用类目猜测（TODO 换 feature/entry 的 AiParser） */
private fun guessCategory(text: String): String = when {
    Regex("午|晚|早|餐|饭|吃|咖|茶|饮").containsMatchIn(text) -> "餐饮"
    Regex("车|地铁|出租|打车|油|票").containsMatchIn(text) -> "交通"
    Regex("衣|鞋|包|购|买").containsMatchIn(text) -> "购物"
    Regex("租|水|电|煤").containsMatchIn(text) -> "居住"
    Regex("影|游戏|唱|玩").containsMatchIn(text) -> "娱乐"
    else -> "餐饮"
}
