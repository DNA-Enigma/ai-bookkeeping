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
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalContext
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
import dagger.hilt.android.EntryPointAccessors
import dev.dzsun.bookkeeping.core.designsystem.*
import dev.dzsun.bookkeeping.feature.ask.AskStage
import dev.dzsun.bookkeeping.feature.ask.AskViewModel
import dev.dzsun.bookkeeping.feature.entry.AddEntryViewModel
import dev.dzsun.bookkeeping.llm.SourceTag
import dev.dzsun.bookkeeping.llm.ThinkingSection
import dev.dzsun.bookkeeping.llm.ThinkingUiState
import dev.dzsun.bookkeeping.llm.ModelProgressLine
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.UiPhase
import dev.dzsun.bookkeeping.llm.rememberUiPhase
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * [source] 非空且不是模型时，气泡下面挂一行来源脚注（含 `src=RULES` 与「去加载模型」），
     * **不带原因的「没有经过 AI 服务」不许再出现**——用户要能看出这次为什么不是模型答的。
     */
    data class Text(
        val fromAgent: Boolean,
        val body: String,
        val source: SourceTag? = null,
    ) : ChatMsg()

    /**
     * [raw] 是用户原话，「修改」时回填输入框用。
     * [source] 是这张卡的来源：模型给的数组走 [SourceTag.Model]，
     * 模型只回了一句话、由本地规则补出来的卡走 [SourceTag.rules]（卡上标出来）。
     */
    data class EntryCard(
        val entry: ParsedEntry,
        val raw: String,
        val source: SourceTag = SourceTag.Model,
    ) : ChatMsg()
}

@Composable
fun ChatScreen(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit = {},
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

    // 本地模型单例（EntryPoint 取，不能自己 new —— 会绕过单例导致重复加载模型）。
    // LocalContext 必须在 remember **外面**读：lambda 不是 @Composable 作用域。
    val appContext = LocalContext.current
    val azhang = remember {
        EntryPointAccessors.fromApplication(appContext, ChatAiEntryPoint::class.java).azhangChat()
    }

    /**
     * 本轮在等本机模型：[waitStart] 是按下发送的那一刻（`SystemClock.elapsedRealtime()`），
     * 阶段由引擎真实状态实时推导（`enginePhase`）。两者合起来就是气泡里的
     * 「解码系统提示 · 已用 1 分 12 秒」——不再是让用户对着三个点干等。
     */
    var modelWait by remember { mutableStateOf(false) }
    var waitStart by remember { mutableStateOf<Long?>(null) }
    val enginePhase = rememberUiPhase(azhang.engineState)

    /** 流式草稿：边生成边显示，收尾时被最终结果替换掉。 */
    var liveReply by remember { mutableStateOf("") }

    /**
     * 「思考过程」折叠区的状态 —— **独立的一份**，只有 [ThinkingSection] 读它。
     * 正文草稿（[liveReply]）与思考素材走两条通道，模型每吐一块不会重排整页。
     */
    val thinking = remember { ThinkingUiState() }

    /**
     * 思考区这一轮要不要显示。
     *
     * 单独一个布尔量是因为它写在 `LazyColumn { }` 的**非 composable 作用域**里：
     * 在那里直接读 `thinking.startedAtMs` 会让整个列表内容失效（正是要避免的抖动）。
     * 它一轮只翻转两次（开始/结束），代价可以忽略。
     */
    var thinkingOn by remember { mutableStateOf(false) }

    /** 上一轮失败的原话 —— 「重试」chip 重发它，而不是让用户重新打一遍。 */
    var retryText by remember { mutableStateOf("") }

    fun scroll() {
        scope.launch {
            delay(60)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }
    }

    fun agentSay(body: String, delayMs: Long = 800, source: SourceTag? = null) {
        scope.launch {
            typing = true; scroll()
            delay(delayMs)
            typing = false
            messages += ChatMsg.Text(true, body, source); scroll()
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

    fun entryParse(raw: String, amountText: String, source: SourceTag) {
        scope.launch {
            typing = true; scroll()
            delay(900)
            typing = false
            val cat = guessCategory(raw)
            val note = raw.replace(Regex("[，,]?\\s*\\d+(?:\\.\\d+)?\\s*"), "")
                .replace(Regex("花了|花|了"), "").trim().ifEmpty { "未备注" }
            // 金额取原文那份（amountText），**不用模型的**——这条路上模型没给出可信金额
            messages += ChatMsg.EntryCard(ParsedEntry(cat, note, amountText), raw, source)
            scroll()
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

    /**
     * 模型不可用时的原逻辑：分类、寒暄、建议全部退回规则，保证永远有回应。
     * [source] 是这一轮为什么走了规则（带原因与代码），会挂在气泡下面。
     */
    fun legacyReply(text: String, intent: ChatIntent, source: SourceTag) {
        when (intent) {
            ChatIntent.Advice -> agentSay(
                "省钱这事得看你的真实账目，我不编数字。\n" +
                    "试着问：「这个月餐饮花了多少」「哪类花得最多」，我看完再一起想办法。",
                900,
                source,
            )

            is ChatIntent.Record -> entryParse(text, intent.amountText, source)
            is ChatIntent.SmallTalk -> agentSay(intent.reply, 600, source)
            // Ask 在 userSay 里已接管；真走到这里也如实说明这次是规则答的
            is ChatIntent.Ask -> agentSay(CHAT_FAILED_TEXT, 600, source)
        }
    }

    fun userSay(text: String) {
        messages += ChatMsg.Text(false, text); scroll()
        val intent = classifyChatInput(text)

        // 查账：直接进本地 SQL 链路 —— **计算交给账本，模型不算数**。
        // 答不出来时 AskViewModel 会再让模型接一句不涉及金额的话。
        if (intent is ChatIntent.Ask) {
            viewModel.onQuestionChange(intent.question)
            viewModel.onAsk()
            return
        }

        // 其余交给本机模型：认出是账就出卡片，否则正常接话。
        // 模型不可用时退回原来的规则判断与话术，聊天页不能哑掉。
        scope.launch {
            typing = true; scroll()
            retryText = ""
            liveReply = ""
            modelWait = true
            waitStart = SystemClock.elapsedRealtime()

            // 含金额的句子（chips 里的「午饭花了 35」就是这一类）**优先走记账解析链路**：
            // 同一个模型、同一份系统提示，但走 MODE_PARSE 那条实测 5/5 全对的路，
            // 且金额保真与规则兜底都在那一条里（见 AzhangChat.parseEntry）。
            if (intent is ChatIntent.Record) {
                val outcome = runCatching { azhang.parseEntry(text) }.getOrNull()
                if (outcome != null && outcome.entries.isNotEmpty()) {
                    modelWait = false; typing = false
                    val e = outcome.entries.first()
                    messages += ChatMsg.EntryCard(
                        ParsedEntry(e.categoryName, e.note.ifBlank { text }, e.amountText),
                        text,
                        source = outcome.source,
                    )
                    if (outcome.entries.size > 1) {
                        messages += ChatMsg.Text(
                            true,
                            "识别到 ${outcome.entries.size} 笔，先记第一笔，其余的再补。",
                        )
                    }
                    scroll()
                    return@launch
                }
                // 认不出账目 → 落回对话链路，别让用户对着一句「没听懂」干等
            }

            thinking.start(waitStart!!)
            thinkingOn = true

            // 流式一轮：边生成边把正文上屏（[AzhangStream.Text]），
            // 超时预算沿用统一的两个分段预算之和，不新造第四个数字。
            var finish: AzhangTurn? = null
            val ok = withTimeoutOrNull(CHAT_TURN_BUDGET_MS) {
                azhang.streamTurn(text).collect { ev ->
                    when (ev) {
                        is AzhangStream.Preparing -> Unit

                        // 思考素材：只喂折叠区，正文气泡与输入框都不参与重组
                        is AzhangStream.Thinking ->
                            thinking.append(ev.delta, SystemClock.elapsedRealtime())

                        is AzhangStream.Text -> {
                            val first = liveReply.isEmpty()
                            liveReply += ev.delta
                            if (first) scroll()
                        }

                        is AzhangStream.Finish -> finish = ev.turn
                    }
                }
                true
            } == true

            modelWait = false
            typing = false
            thinking.finish(SystemClock.elapsedRealtime())
            val draft = liveReply
            liveReply = ""
            // finish 在闭包里被赋值过，Kotlin 不允许对它做智能转换 —— 拷成本地不可变引用
            val result = finish
            when {
                // 超时：已经吐出来的部分留着，一个字都没出才说超时（并分清「还在准备」和「真出不来」）
                !ok -> {
                    retryText = text
                    val reason = if (azhang.isReady) CHAT_GEN_TIMEOUT_TEXT else CHAT_PREPARING_TIMEOUT_TEXT
                    messages += ChatMsg.Text(
                        true,
                        if (draft.isEmpty()) reason else "$draft\n\n—— $reason",
                        source = SourceTag.rulesNotReady(reason, SourceTag.CODE_BAD_OUTPUT),
                    )
                }

                result is AzhangTurn.Entry -> {
                    val e = result.entries.first()
                    messages += ChatMsg.EntryCard(
                        ParsedEntry(e.categoryName, e.note.ifBlank { text }, e.amountText),
                        text,
                        source = result.source,
                    )
                    if (result.entries.size > 1) {
                        messages += ChatMsg.Text(true, "识别到 ${result.entries.size} 笔，先记第一笔，其余的再补。")
                    }
                }

                result is AzhangTurn.Reply -> {
                    // 模型那句话照常显示；它没给数组时，保底规则补出来的卡片跟在后面
                    // （见 withRulesEntryFallback）——这样「账一定记得上」不依赖模型守约。
                    messages += ChatMsg.Text(true, result.body)
                    if (result.entries.isNotEmpty()) {
                        val e = result.entries.first()
                        messages += ChatMsg.EntryCard(
                            entry = ParsedEntry(
                                category = e.categoryName,
                                note = e.note.ifBlank { text },
                                amountText = e.amountText,
                            ),
                            raw = text,
                            // 保底补出来的卡来源是规则（带原因与代码），卡上要标出来
                            source = result.entriesSource ?: SourceTag.Model,
                        )
                        if (result.entries.size > 1) {
                            messages += ChatMsg.Text(true, "识别到 ${result.entries.size} 笔，先记第一笔，其余的再补。")
                        }
                    }
                }

                else -> {
                    // 降级回答照旧给（规则识别/寒暄话术），但**来源与原因都要摆出来**：
                    // 统一走 SourceTag 的脚注（含 src=RULES 与代码），气泡下面还能点「去加载模型」。
                    retryText = text
                    val reason = (result as? AzhangTurn.Unavailable)
                        ?.reason?.takeIf { it.isNotBlank() } ?: CHAT_FAILED_TEXT
                    val tag = SourceTag.rules(reason, (result as? AzhangTurn.Unavailable)?.code)
                    legacyReply(text, intent, tag)
                    scroll()
                }
            }
            scroll()
        }
    }

    LaunchedEffect(Unit) {
        agentSay("下午好。想记账、查账，还是聊聊怎么省钱？说一句就行。", 500)
        // 预热本机模型：加载模型 + 解码系统提示要花时间，
        // 等用户打完字再开始就太晚了。失败不打紧，真调用时会重试并降级。
        azhang.warmUp()
    }

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
                    if (modelWait) {
                        // 阶段 + 已用时，来自引擎真实状态（加载权重 / 解码系统提示 / 生成）
                        ModelProgressLine(
                            phase = enginePhase,
                            startedAtMs = waitStart,
                            showHint = false,
                            color = p.accent,
                        )
                    } else {
                        Text(
                            "贴心管家 · 在线",
                            style = TextStyle(fontSize = 11.sp, letterSpacing = 1.sp),
                            color = p.ink3,
                        )
                    }
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
                    is ChatMsg.Text -> TextBubble(msg.fromAgent, msg.body, msg.source, onOpenSettings)
                    is ChatMsg.EntryCard -> EntryBubble(
                        entry = msg.entry,
                        source = msg.source,
                        onConfirm = { confirmEntry(msg) },
                        onEdit = {
                            // 「修改」= 把原话回填输入框，让用户改完重发。
                            input = msg.raw
                            scroll()
                        },
                    )
                }
            }
            // 思考过程：默认收起一行摘要，展开是局部滚动区。
            // 进来的只有 [ThinkingUiState] 这一个稳定对象 —— 内容在组件内部读，
            // 所以模型每吐一块只重组这一项，气泡/输入框/chips 都不跟着重排。
            if (thinkingOn) item { ThinkingSection(thinking) }

            // 流式草稿：生成中的正文逐字上屏，不等终态一次出
            if (liveReply.isNotEmpty()) item { TextBubble(true, liveReply) }
            if (typing) item {
                when {
                    // 等本机模型 → 阶段 + 已用时（不是三个点干等）
                    modelWait -> WaitBubble(enginePhase, waitStart)

                    // 问账链路 → 已经交给 AI 服务判定，也给阶段 + 已用时
                    askState.stage is AskStage.Interpreting -> WaitBubble(UiPhase.DISPATCH, askState.waitStart)

                    else -> TypingBubble()
                }
            }
        }

        /* 建议问题（失败后第一个是「重试」，重发上一条原话） */
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (retryText.isNotEmpty()) {
                ArtChip("重试", selected = false, onClick = { userSay(retryText) })
            }
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
private fun TextBubble(
    fromAgent: Boolean,
    body: String,
    source: SourceTag? = null,
    onOpenSettings: () -> Unit = {},
) {
    val p = Art.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromAgent) Arrangement.Start else Arrangement.End) {
        if (fromAgent) { SealBadge("账", size = 28.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.widthIn(max = 300.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
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
            // 来源脚注：**只在这一轮没走模型时出现**。
            // 文案统一由 SourceTag 出（含具体原因与代码），界面不另编一句——
            // 「没有经过 AI 服务」那种不带原因的说法在这里构造不出来。
            if (source != null && !source.isModel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    source.footnote(),
                    style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
                    color = p.ink3,
                )
                Text(
                    "去加载模型",
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = p.accent,
                    modifier = Modifier.clickable(onClick = onOpenSettings).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EntryBubble(
    entry: ParsedEntry,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    source: SourceTag = SourceTag.Model,
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
                // 来源标进卡片：规则补出来的卡要让用户看得见，别当成模型认出来的
                val tags = buildList {
                    add(entry.category); add(entry.note); add("今天")
                    // 规则兜底补出来的卡要让用户看得见来源与代码，别当成模型认出来的
                    if (!source.isModel) {
                        add(source.shortLabel)
                        source.code?.let(::add)
                    }
                }
                tags.forEach { tag ->
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

/**
 * 等待气泡：外形同 [TypingBubble]，内容换成**阶段 + 已用时 + 预期提示**。
 * 阶段由调用方从真实状态推导（`phaseOf(engineState)` / 已知的调度阶段），这里只负责显示，
 * 所以对话页、问账页看到的文案是同一套，不会各写各的。
 */
@Composable
private fun WaitBubble(phase: UiPhase, startedAtMs: Long?) {
    val p = Art.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SealBadge("账", size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(p.radiusL))
                .background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(p.radiusL))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ModelProgressLine(phase = phase, startedAtMs = startedAtMs, color = p.ink2)
        }
    }
}

/**
 * 本轮超时 = 加载预算 + 生成预算 —— **只引用，不定义**。
 * `PANEL_TURN_BUDGET_MS` 已经是这两个分段预算之和（见 AiFloatPanel.kt），
 * 对话页和面板走的是同一条链，必须用同一个数。
 */
private val CHAT_TURN_BUDGET_MS = PANEL_TURN_BUDGET_MS

/** 整轮超过预算、一个字都没吐出来的失败文案：**原因 + 下一步**（重试入口是下面的「重试」chip）。 */
private val CHAT_GEN_TIMEOUT_TEXT =
    "这次生成超时了（超过 ${SparkSession.GEN_BUDGET_MS / 1000} 秒没出结果）。\n" +
        "可以在下面点「重试」再发一次。"

/**
 * 同样是超时，但模型**还在初始化**（首次冷启动实测 267 秒）——
 * 必须和上面那句分开：这不是「答不上来」，是「还在准备」。
 */
private val CHAT_PREPARING_TIMEOUT_TEXT =
    "模型还在准备（首次约 4 分钟），这次没等到它就绪。\n" +
        "可以在下面点「重试」再发一次。"

/** 连原因都没拿到时的兜底：仍然带上「下一步做什么」，不写一句孤零零的「失败」。 */
private const val CHAT_FAILED_TEXT =
    "这次没答上。\n可以在下面点「重试」再发一次。"

/** 演示用类目猜测（TODO 换 feature/entry 的 AiParser） */
private fun guessCategory(text: String): String = when {
    Regex("午|晚|早|餐|饭|吃|咖|茶|饮").containsMatchIn(text) -> "餐饮"
    Regex("车|地铁|出租|打车|油|票").containsMatchIn(text) -> "交通"
    Regex("衣|鞋|包|购|买").containsMatchIn(text) -> "购物"
    Regex("租|水|电|煤").containsMatchIn(text) -> "居住"
    Regex("影|游戏|唱|玩").containsMatchIn(text) -> "娱乐"
    else -> "餐饮"
}
