package dev.dzsun.bookkeeping.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 消息流 + 由它折出来的轮次。两个字段**一起变**，所以装在一个持有者里，
 * 调用方不要把其中一个单独拿出来当状态——那样会读到「消息新、轮次旧」的中间态。
 *
 * 写入一律走 [add]：直接动 [messages] 会绕过轮次重算，卡片就会少一项或多一项。
 */
@Stable
internal class ChatFeed {
    val messages = mutableStateListOf<ChatMsg>()
    var rounds by mutableStateOf<List<ChatRound>>(emptyList())

    fun add(msg: ChatMsg) {
        messages.add(msg)
        rounds = groupRounds(messages)
    }
}

/**
 * 流式正文草稿的容器。
 *
 * ## 为什么不直接当参数传，也不在页面顶层读
 *
 * 草稿每来一块就变一次。它要是**页面顶层的 mutableStateOf**（改造前就是
 * `var liveReply by remember { mutableStateOf("") }`），读它的 `LazyColumn { }`
 * 内容作用域整块失效——气泡、输入框、chips、滚动全跟着重画。
 *
 * 同一件事 [ThinkingSection] 已经用「稳定对象进、内容在组件内部读」解决过一次，
 * 这里照搬，而且不需要 CompositionLocal：传**对象**（引用恒定 → 参数比较相等 →
 * 其余卡片整体跳过），卡片**只在 live 时**才去读 `.text`。没读就没有订阅，
 * 快照系统只让真正读它的那一项失效。
 *
 * [thinkingOn] 那个布尔量仍留在页面顶层：一轮只翻转两次，读它不心疼，
 * 而它必须在外层决定「这一轮要不要挂思考区」。
 */
@Stable
internal class DraftBuffer {
    var text by mutableStateOf("")
}

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

    /**
     * 扁平消息流 + 由它折出来的轮次，装在**一个持有者的两个字段**里，不是两条独立状态。
     *
     * 理由：轮次必须在写入 messages 的同一刻重算。分开放就会出现「新消息已经上屏、
     * ids 还是旧的」那一帧，LazyColumn 按旧 key 少一项、滚动位置跟着跳。
     * 两个 `mutableStateOf` 在同一个非 composable lambda 里连续写，快照提交是原子的，
     * 读侧只看到「旧旧」和「新新」两种状态。
     *
     * 历史**不进 saveable**：这个页面从来没有历史持久层（SourceTag / EntryCard
     * 的可空字段要各自往返编码，是另一件事），进程重建后回到开场白是既有行为，
     * 折叠状态被 [pruneExpanded] 清掉正好与之自洽。
     */
    val feed = remember { ChatFeed() }
    val messages = feed.messages
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    /**
     * 用户手动点过的轮次。键是轮次 id，值是展开与否；没点过的走
     * [isRoundExpandedByDefault]（最近 [DEFAULT_EXPANDED_ROUNDS] 轮展开）。
     *
     * 存成一行字符串走默认 String 通道——`Map<String, Boolean>` 进不了 Bundle。
     * 切后台切回来、旋转、进程重建后重新进入，点过的开合都不丢。
     */
    var overrideRaw by rememberSaveable { mutableStateOf("") }
    /** 已提交过入账的卡片 key，见 [entryCardKey]。同样是「收起即丢 remember」的受害者。 */
    var confirmedRaw by rememberSaveable { mutableStateOf("") }

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

    /**
     * 流式草稿。装进 [DraftBuffer] 而不是裸的 `mutableStateOf`，理由写在
     * 那个类的注释里：正文每来一块都变，页面顶层读它就是把整页卷进重画。
     * 只有正在生成的那一张卡片会读 `.text`。
     */
    val draft = remember { DraftBuffer() }

    /**
     * 「思考过程」折叠区的状态 —— **独立的一份**，只有 [ThinkingSection] 读它。
     * 正文草稿（[draft]）与思考素材走两条通道，模型每吐一块不会重排整页。
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

    /**
     * 本轮挂哪张卡片：思考区、流式草稿、等待气泡都只认这一个 id。
     *
     * 为什么不能省：改造前那三样都是 `LazyColumn` 末尾的独立 item，归不进任何轮次。
     * 折叠之后必须知道"挂在哪张卡里"，而**不能靠"最后一轮"**——开场白还在慢慢出来的
     * 时候用户已经发了下一句，那时上一轮的「查看思考过程」会被算到新一轮头上，
     * 用户看到一个跟自己问题无关的思考过程。问账链路（ChatIntent.Ask）也靠它
     * 把「已交给 AI 服务」的等待气泡放进正确的一轮。
     */
    var turnRoundId by remember { mutableStateOf<String?>(null) }

    /**
     * [turnRoundId] 里"此刻真的在跑"的那一份：只有它非空时才算 live（强制展开、
     * 挂草稿与等待气泡）。一轮结束就置回 null——否则结束后的卡片还顶着 live，
     * 用户再也收不起它（live 优先于收起是没有的，但「生成中」的提示会赖着不走）。
     */
    var runningRoundId by remember { mutableStateOf<String?>(null) }

    /** 上一轮失败的原话 —— 「重试」chip 重发它，而不是让用户重新打一遍。 */
    var retryText by remember { mutableStateOf("") }

    fun scroll() {
        scope.launch {
            delay(60)
            // 滚到「最后一张卡片」这一项（+1 是越界一格，动画会停在末项），
            // 而不是滚到消息条数——折叠后一项里装着好几条消息，用条数会滚过头。
            val count = feed.rounds.size
            if (count > 0) listState.animateScrollToItem(count)
        }
    }

    /**
     * 点标题栏。写入前先过一次 [pruneExpanded]：id 是 `round-{下标}`，
     * 历史里如果哪天出现删除，旧键就会指着一条不存在的轮次，字符串无限膨胀。
     * 现在没有删除，但这一行让那个前提破了也不会出错。
     *
     * 记的是**取反后的绝对值**，不是"记一次收起"：正在生成的那轮走 live 强制展开，
     * 用户此刻点它就是把默认值写成 false，生成结束后那个选择照样生效。
     */
    fun toggleRound(roundId: String, currentlyExpanded: Boolean) {
        val next = decodeExpanded(overrideRaw).toMutableMap()
        next[roundId] = !currentlyExpanded
        overrideRaw = encodeExpanded(pruneExpanded(feed.rounds.map { it.id }, next))
        // **不滚**。原来每次上屏都 scroll()，是为了让新消息进视野；点标题栏是用户
        // 自己在挑要看的那一轮，滚到底部会把他刚点开的那张（多半在中间）顶出屏幕。
        // 高度变化由 AnimatedVisibility 就地动画，不需要列表配合。
    }

    /** 这张待确认卡是否已经按过「确认入账」——见 [entryCardKey]。 */
    fun markConfirmed(cardKey: String) {
        confirmedRaw = encodeConfirmed(decodeConfirmed(confirmedRaw).toList() + cardKey)
    }

    /**
     * 开一轮：把刚追加的那条用户消息所在轮次认领为「本轮」。
     * 必须在 `feed.add` **之后**调用——轮次 id 由分组算出来，早一步就拿不到新一轮。
     */
    fun claimTurn() {
        val id = feed.rounds.lastOrNull()?.id
        turnRoundId = id
        runningRoundId = id
    }

    /** 一轮收尾（成功、失败、被取消都算）：不再 live，卡片才可以被收起。 */
    fun endTurn() { runningRoundId = null }

    fun agentSay(body: String, delayMs: Long = 800, source: SourceTag? = null) {
        scope.launch {
            typing = true; scroll()
            delay(delayMs)
            typing = false
            feed.add(ChatMsg.Text(true, body, source)); scroll()
        }
    }

    // 问账流水线的回答 → 消息流（stage 每次迁移都触发）
    LaunchedEffect(askState.stage) {
        when (val s = askState.stage) {
            is AskStage.Interpreting -> { typing = true; scroll() }
            is AskStage.Answered -> {
                typing = false
                endTurn()
                val a = s.answer
                val text = buildString {
                    append(a.headline)
                    if (a.detail.isNotEmpty()) append("\n").append(a.detail.joinToString("\n"))
                    a.footnote?.let { append("\n—— ").append(it) }
                }
                feed.add(ChatMsg.Text(true, text)); scroll()
            }
            is AskStage.Unavailable -> {
                typing = false
                endTurn()
                // s.reason 已是白名单文案（见 UserFacingErrors），不透传服务端原文。
                // 但光说「换个问法」没用——用户不知道换成哪种，所以附一个必然能成的例子。
                feed.add(ChatMsg.Text(true, askUnavailableReply(s.reason)))
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
            feed.add(ChatMsg.EntryCard(ParsedEntry(cat, note, amountText), raw, source))
            scroll()
        }
    }

    /**
     * 对话里「确认入账」：交给 [AddEntryViewModel.saveQuickEntry] 真写库。
     * 成功、失败各说各话——**没写进去却说「记好了」是最伤信任的一种 bug**。
     *
     * [cardKey] 非空时把这张卡记进「已提交」集合（saveable）。原来那个 `done`
     * 是 EntryBubble 里的 `remember`：卡片一旦收起就离开组合，remember 被丢掉，
     * 再展开就变回可点的「确认入账」——**同一笔能记第二遍**。
     */
    fun confirmEntry(card: ChatMsg.EntryCard, cardKey: String?) {
        if (cardKey != null) markConfirmed(cardKey)
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
        feed.add(ChatMsg.Text(false, text)); scroll()
        val intent = classifyChatInput(text)

        // 查账：直接进本地 SQL 链路 —— **计算交给账本，模型不算数**。
        // 答不出来时 AskViewModel 会再让模型接一句不涉及金额的话。
        if (intent is ChatIntent.Ask) {
            claimTurn()
            viewModel.onQuestionChange(intent.question)
            viewModel.onAsk()
            return
        }

        // 其余交给本机模型：认出是账就出卡片，否则正常接话。
        // 模型不可用时退回原来的规则判断与话术，聊天页不能哑掉。
        scope.launch {
            claimTurn()
            typing = true; scroll()
            retryText = ""
            draft.text = ""
            modelWait = true
            waitStart = SystemClock.elapsedRealtime()

            // 含金额的句子（chips 里的「午饭花了 35」就是这一类）**优先走记账解析链路**：
            // 同一个模型、同一份系统提示，但走 MODE_PARSE 那条实测 5/5 全对的路，
            // 且金额保真与规则兜底都在那一条里（见 AzhangChat.parseEntry）。
            if (intent is ChatIntent.Record) {
                val outcome = runCatching { azhang.parseEntry(text) }.getOrNull()
                if (outcome != null && outcome.entries.isNotEmpty()) {
                    modelWait = false; typing = false; endTurn()
                    val e = outcome.entries.first()
                    feed.add(
                        ChatMsg.EntryCard(
                            ParsedEntry(e.categoryName, e.note.ifBlank { text }, e.amountText),
                            text,
                            source = outcome.source,
                        ),
                    )
                    if (outcome.entries.size > 1) {
                        feed.add(
                            ChatMsg.Text(
                                true,
                                "识别到 ${outcome.entries.size} 笔，先记第一笔，其余的再补。",
                            ),
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
                            val first = draft.text.isEmpty()
                            draft.text += ev.delta
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
            // 局部名 partial：这里的 `draft` 是那个稳定容器，别再占用同名
            val partial = draft.text
            draft.text = ""
            // finish 在闭包里被赋值过，Kotlin 不允许对它做智能转换 —— 拷成本地不可变引用
            val result = finish
            when {
                // 超时：已经吐出来的部分留着，一个字都没出才说超时（并分清「还在准备」和「真出不来」）
                !ok -> {
                    retryText = text
                    val reason = if (azhang.isReady) CHAT_GEN_TIMEOUT_TEXT else CHAT_PREPARING_TIMEOUT_TEXT
                    feed.add(
                        ChatMsg.Text(
                            true,
                            if (partial.isEmpty()) reason else "$partial\n\n—— $reason",
                            source = SourceTag.rulesNotReady(reason, SourceTag.CODE_BAD_OUTPUT),
                        ),
                    )
                }

                result is AzhangTurn.Entry -> {
                    val e = result.entries.first()
                    feed.add(
                        ChatMsg.EntryCard(
                            ParsedEntry(e.categoryName, e.note.ifBlank { text }, e.amountText),
                            text,
                            source = result.source,
                        ),
                    )
                    if (result.entries.size > 1) {
                        feed.add(ChatMsg.Text(true, "识别到 ${result.entries.size} 笔，先记第一笔，其余的再补。"))
                    }
                }

                result is AzhangTurn.Reply -> {
                    // 模型那句话照常显示；它没给数组时，保底规则补出来的卡片跟在后面
                    // （见 withRulesEntryFallback）——这样「账一定记得上」不依赖模型守约。
                    feed.add(ChatMsg.Text(true, result.body))
                    if (result.entries.isNotEmpty()) {
                        val e = result.entries.first()
                        feed.add(
                            ChatMsg.EntryCard(
                                entry = ParsedEntry(
                                    category = e.categoryName,
                                    note = e.note.ifBlank { text },
                                    amountText = e.amountText,
                                ),
                                raw = text,
                                // 保底补出来的卡来源是规则（带原因与代码），卡上要标出来
                                source = result.entriesSource ?: SourceTag.Model,
                            ),
                        )
                        if (result.entries.size > 1) {
                            feed.add(ChatMsg.Text(true, "识别到 ${result.entries.size} 笔，先记第一笔，其余的再补。"))
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

        /* 消息流：一轮一张卡，点标题栏展开/收起 */
        // 折叠状态与「已提交」集合在页面层各解一次。它们是**点一下/按一次才变**的量，
        // 不是每块 token 都变的量——在这里读不会造成生成期的抖动。
        val overrides = decodeExpanded(overrideRaw)
        val confirmed = decodeConfirmed(confirmedRaw)
        // running 只看 typing，**不能把 thinkingOn 并进来**：思考区结束后仍要留着那一行
        // 「查看思考过程」，thinkingOn 从此一直是 true。并进 running 的话最后一轮
        // 永远强制展开，用户再也收不起刚看完的那轮。
        val running = typing
        val liveRoundId = if (running) runningRoundId ?: feed.rounds.lastOrNull()?.id else null
        val thinkingRoundId = if (thinkingOn) turnRoundId else null
        // 等待气泡显示哪个阶段：本机模型在跑就用引擎阶段，问账链路用调度阶段。
        // 口径与改造前那三条 when 分支完全一致，只是从「列表末尾独立 item」
        // 搬进卡片内部——搬进来才会跟着轮次收起/展开，也不会挂错轮次。
        val waitPhase = when {
            modelWait -> enginePhase
            askState.stage is AskStage.Interpreting -> UiPhase.DISPATCH
            else -> null
        }
        val waitSince = if (modelWait) waitStart else askState.waitStart
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // key 用轮次 id：新消息追加时前面的 key 不变，折叠态与动画都不会串位
            itemsIndexed(feed.rounds, key = { _, round -> round.id }) { index, round ->
                val roundIsLive = round.id == liveRoundId
                val expanded = resolveRoundExpanded(
                    index = index,
                    total = feed.rounds.size,
                    roundId = round.id,
                    overrides = overrides,
                    live = roundIsLive,
                )
                RoundCard(
                    round = round,
                    expanded = expanded,
                    live = roundIsLive,
                    draft = if (roundIsLive) draft else null,
                    thinking = if (round.id == thinkingRoundId) thinking else null,
                    waitPhase = if (roundIsLive) waitPhase else null,
                    waitStart = if (roundIsLive) waitSince else null,
                    confirmed = confirmed,
                    onToggle = { toggleRound(round.id, expanded) },
                    onOpenSettings = onOpenSettings,
                    onConfirmEntry = { card, index -> confirmEntry(card, entryCardKey(index)) },
                    onEditEntry = { raw ->
                        // 「修改」= 把原话回填输入框，让用户改完重发。
                        input = raw
                        scroll()
                    },
                )
            }
            // 一句都还没上屏（首次的开场白还在 delay 里）时没有卡片可挂，单独给一项
            if (running && feed.rounds.isEmpty()) item { TypingBubble() }
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

/* ---------------- 轮次卡片 ---------------- */

/**
 * 一轮对话 = 一张卡。标题恒为一行，点开是完整的一轮（用户那句 + 模型的全部产出），
 * 再点收起。
 *
 * ## 三条口径
 *
 * 1. **收起时 body 整块离开组合**，不是 `alpha = 0f` 藏起来。
 *    那些卡片里有 `remember` 的状态——EntryBubble 的 `done`。留在组合里那个 `done`
 *    就还在，收起再展开仍然显示「已提交」；但「留在组合里」意味着 LazyColumn
 *    要为它测量、八轮对话占的内存和布局一分都不省，那这次折叠就白做了。
 *    所以 body 真的拆掉，代价是 `done` 没了——那个状态因此搬到页面层的 saveable
 *    集合里（[entryCardKey]），见 [ChatScreen] 的 `confirmedRaw`。
 *
 * 2. **展开状态是算出来的，不是存进来的**（[resolveRoundExpanded]）。
 *    这样「新轮次默认展开三轮」和「用户点过的那轮听用户的」不会互相覆盖，
 *    也不会随着新消息在开合之间来回跳。
 *
 * 3. **live 的那一张才读草稿**。[draft] 是稳定对象，非 live 的卡片传的是 null，
 *    连读都不读——没有订阅就没有重组。生成期只有「一张卡片 + 思考区」重画。
 */
@Composable
private fun RoundCard(
    round: ChatRound,
    expanded: Boolean,
    live: Boolean,
    draft: DraftBuffer?,
    thinking: ThinkingUiState?,
    waitPhase: UiPhase?,
    waitStart: Long?,
    confirmed: Set<String>,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmEntry: (ChatMsg.EntryCard, Int) -> Unit,
    onEditEntry: (String) -> Unit,
) {
    val p = Art.colors
    val shape = RoundedCornerShape(p.radiusL)
    // 容器用 p.bg（= 页面底色）+ 发丝线边框，而不是 p.card：
    // 卡内 assistant 气泡本身就是 p.card，两层同色就没有层次了。
    // Zen/Editorial 这两套的 card 与 surface 差不到 5%，靠色块分不出轮次边界，
    // 边框才是这里的分隔手段（与主题里 stageAsCard=false 的「发丝线通栏」口径一致）。
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.bg)
            .border(1.dp, p.line, shape),
    ) {
        RoundHeader(round, expanded, live, onToggle)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            // 轮次内部不再要项间距——气泡之间靠自己的外边距，这里用 Column 排
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                round.items.forEachIndexed { offset, msg ->
                    when (msg) {
                        is ChatMsg.Text -> TextBubble(
                            fromAgent = !msg.isUserTurn,
                            body = msg.body,
                            source = msg.source,
                            onOpenSettings = onOpenSettings,
                        )
                        is ChatMsg.EntryCard -> EntryBubble(
                            entry = msg.entry,
                            source = msg.source,
                            done = entryCardKey(round.firstItemIndex + offset) in confirmed,
                            onConfirm = { onConfirmEntry(msg, round.firstItemIndex + offset) },
                            onEdit = { onEditEntry(msg.raw) },
                        )
                    }
                }
                // 思考过程：默认收起一行摘要，展开是局部滚动区。
                // 只有被本轮认领的那张卡传得进对象（其他轮次传 null，压根不渲染），
                // 所以上一轮那行「查看思考过程」不会被算到新一轮头上。
                if (thinking != null) ThinkingSection(thinking)
                // 流式草稿：生成中的正文逐字上屏，不等终态一次出。
                // 读 draft.text 是本轮唯一的每-token 订阅点。
                val liveDraft = draft?.text
                if (!liveDraft.isNullOrEmpty()) TextBubble(true, liveDraft)
                // 等待气泡：阶段 + 已用时（不是三个点干等）。放在草稿之后，
                // 因为「还在等什么」比「已经出了什么」更该贴在最新一行。
                if (waitPhase != null) WaitBubble(waitPhase, waitStart)
            }
        }
    }
}

/**
 * 标题栏：一行用户消息 + 箭头。整行可点。
 *
 * 收起时的提示文案（`2 条回应 · 生成中`）用 [roundCollapsedHint]，
 * 展开时不显示——展开着还写「几条回应」是废话。
 */
@Composable
private fun RoundHeader(
    round: ChatRound,
    expanded: Boolean,
    live: Boolean,
    onToggle: () -> Unit,
) {
    val p = Art.colors
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "roundArrow")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            roundTitle(round.prompt, round.items),
            modifier = Modifier.weight(1f),
            style = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, fontFamily = Art.type.body),
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val hint = roundCollapsedHint(round.replyCount, live)
        if (!expanded && hint.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = TextStyle(fontSize = 11.sp), color = p.ink3, maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        // 箭头用旋转的 ▾ 表示，和 ThinkingSection 同一套——不为两个方向引图标资源
        Text(
            "▾",
            style = TextStyle(fontSize = 12.sp),
            color = p.ink3,
            modifier = Modifier.rotate(arrow),
        )
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
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = if (fromAgent) Arrangement.Start else Arrangement.End) {
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

/**
 * 待确认卡片。
 *
 * [done] 现在由外面给——原来这里是 `var done by remember { mutableStateOf(false) }`。
 * 折叠之后卡片会真的离开组合，remember 被丢掉，**「已提交」会退回可点的「确认入账」**，
 * 于是同一笔能记第二遍。所以那份状态搬到页面层的 saveable 集合里（见 [entryCardKey]）。
 * 这不是洁癖，是这次改动直接引入的风险：不搬，折叠功能本身就是个重复记账的入口。
 */
@Composable
private fun EntryBubble(
    entry: ParsedEntry,
    done: Boolean,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    source: SourceTag = SourceTag.Model,
) {
    val p = Art.colors
    // 按过任一按钮就锁住：既不能再点「确认入账」，也不能重复点「修改」。
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Start) {
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
