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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.dzsun.bookkeeping.llm.modelWorkOf
import dev.dzsun.bookkeeping.llm.TurnResult
import kotlinx.coroutines.flow.combine
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
import android.os.SystemClock
import dev.dzsun.bookkeeping.core.designsystem.Art
import dev.dzsun.bookkeeping.core.designsystem.SealBadge
import dev.dzsun.bookkeeping.feature.entry.AddEntryViewModel
import dev.dzsun.bookkeeping.llm.ModelProgressLine
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.ThinkingSection
import dev.dzsun.bookkeeping.llm.ThinkingUiState
import dev.dzsun.bookkeeping.llm.UiPhase
import dev.dzsun.bookkeeping.llm.rememberUiPhase
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
    /**
     * 进程级的问答执行器 + 待交付回答。
     *
     * 取不到（非 Hilt 宿主，理论上不会发生）就退回**原来的组合作用域**跑：
     * 功能与改动前一致，只是切走会断——宁可用旧行为也不能让面板打不开。
     */
    val work = remember { modelWorkOf(context) }
    val addEntry: AddEntryViewModel = hiltViewModel()

    var input by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(PanelStatus.DONE) }
    var entry by remember { mutableStateOf<dev.dzsun.bookkeeping.feature.entry.ParsedEntry?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 上一次真正发出去的那句 —— 失败态的「重试」重发它，而不是让用户重新打一遍。 */
    var lastText by remember { mutableStateOf("") }

    /**
     * 本轮等待的起点（按下发送的那一刻，`SystemClock.elapsedRealtime()`）。
     * 阶段由引擎真实状态推导，这里只负责「已用时」的分母。
     */
    var turnStart by remember { mutableStateOf<Long?>(null) }
    val enginePhase = rememberUiPhase(azhang.engineState)

    /**
     * 「思考过程」折叠区的状态 —— **独立的一份**，只有 [ThinkingSection] 读它。
     * 正文草稿（[reply]）与思考素材（[AzhangStream.Thinking]）走两条通道：
     * 模型每吐一块只重组折叠区自己，头部、卡片、输入框、去处 chips 都不参与。
     */
    val thinking = remember { ThinkingUiState() }

    /**
     * 思考区要不要显示。一轮只翻转一次（**收到第一块思考素材时**），
     * 面板顶层**不读** `thinking.text` —— 在那读等于让整块面板订阅生成节奏，
     * 正是要避免的抖动；而一个字都还没产出时整块不出现（问账页同口径，
     * 那时头部与正文区的 `ModelProgressLine` 已经在说阶段了）。
     * 完成后留在收起态（摘要变「查看思考过程」），下一轮 [ThinkingUiState.start] 复用。
     */
    var thinkingOn by remember { mutableStateOf(false) }

    // 打开面板就预热模型：1GB 模型 + 系统提示解码要花时间，
    // 等用户打完字再开始加载，那条消息就白等了。
    LaunchedEffect(Unit) { azhang.warmUp() }

    /*
     * ── 接回现场：切走再回来，回答不能丢 ─────────────────────────────
     *
     * 面板的显示状态**全部由这一处订阅推导**，不在别的地方各写一份。
     * 两个真源合起来正好覆盖「回来时可能发生的一切」：
     *
     *  - `runner.turn` —— 还在跑的那一轮（草稿、思考素材、起点）。
     *  - `controller.pending` —— 已经落定、还没被界面取走的那一条。
     *
     * 后者的两条来路是**同一个字段**，界面不需要知道中间发生过什么：
     *   1. 进程还活着（切后台、或收起面板又打开）：那一轮由 [ModelWorkRunner]
     *      在单例作用域里跑完了，直接在这条流上收到；
     *   2. 进程被系统杀过：内存那份跟着没了，[ModelWorkController.attach] 在
     *      Application.onCreate 把磁盘那条播回——**界面走的还是同一个字段**。
     *      这就是落盘的全部意义：只做前者，冷启动照样丢答案。
     *
     * `pending` 优先于 `turn`：正常收场顺序是「先落 pending、后清 turn」，
     * StateFlow 可能把中间态（两个都有值）交上来；答案既然已经存在，
     * 就不该显示成「还在生成」。
     *
     * `else -> Unit` 那条不是偷懒：取走回答后 [ModelWorkController.consumePending]
     * 会让两个源同时归 null，那是「这一轮已经交付完了」，不是「没有内容可显示」——
     * 清空就把刚给出的答案擦掉了。
     */
    LaunchedEffect(work) {
        val w = work ?: return@LaunchedEffect
        combine(w.first.turn, w.second.pending) { turn, pending -> turn to pending }
            .collect { (t, p) ->
                when {
                    p != null -> {
                        lastText = p.question
                        busy = false
                        turnStart = null
                        // 播回的那句要交代来路——「2 分钟前」不是装饰：
                        // 用户切走十分钟回来，得知道这句是他走之前那句问话的答案。
                        reply = p.ageLine?.let { "${p.answer}\n\n（这句是 $it 答完的）" } ?: p.answer
                        // 卡片从 payload 里解出来：**只恢复文字的话，用户回来看到
                        // 识别结果却点不了「记下来」，那还是丢了一次**。
                        entry = decodePanelCard(p.payload)
                        status = if (p.ok) PanelStatus.DONE else PanelStatus.FAILED
                        if (entry != null) status = PanelStatus.DONE
                        thinking.finish(SystemClock.elapsedRealtime())
                        thinking.collapse()
                        // **取走即清**（内存 + 磁盘一起）：留着的话下次冷启动会再播一遍，
                        // 同一句话答两次是最容易被当成 bug 报上来的。
                        w.second.consumePending()
                    }

                    t != null -> {
                        lastText = t.question
                        busy = true
                        turnStart = t.startedAtMs
                        reply = t.draft
                        status = if (t.draft.isEmpty()) PanelStatus.PREPARING else PanelStatus.STREAMING
                        // 思考素材：只喂折叠区（内部走 ThinkingThrottle，≥150ms 才刷一次），
                        // 面板其余部分不随生成节奏重组。收到第一块才把折叠区挂出来——
                        // 纯准备阶段头部/正文区的 ModelProgressLine 已经在说进度了。
                        if (t.thought.isNotEmpty()) {
                            thinkingOn = true
                            thinking.append(t.thought, SystemClock.elapsedRealtime())
                        }
                    }

                    else -> Unit
                }
            }
    }

    fun startTurn(text: String) {
        if (text.isEmpty() || busy) return
        // 立刻把上一轮的显示清掉：不清的话用户会以为新那句已经被答了。
        saved = null
        entry = null
        reply = ""
        thinking.start(SystemClock.elapsedRealtime())

        val w = work
        if (w != null) {
            // 交给进程级执行器：**收起面板、跳页、切后台都掐不掉它**。
            // 草稿由 runner.turn 累积（上面那个订阅在渲染），这里不再自己拼字符串——
            // 两处各拼一份迟早会漂。
            w.first.start(text) { sink ->
                var finish: AzhangTurn? = null
                val streamed = withTimeoutOrNull(PANEL_TURN_BUDGET_MS) {
                    azhang.streamTurn(text).collect { ev ->
                        when (ev) {
                            is AzhangStream.Preparing -> Unit
                            is AzhangStream.Thinking -> sink.thought(ev.delta)
                            is AzhangStream.Text -> sink.answer(ev.delta)
                            is AzhangStream.Finish -> finish = ev.turn
                        }
                    }
                    true
                } == true
                // 正文以**订阅里的草稿**为准（它是这条流的唯一真源），
                // 卡片与成败由纯函数从终局判定推出来——两处共用同一个核。
                val body = panelTurnBody(
                    finished = finish,
                    streamed = streamed,
                    draft = w.first.turn.value?.draft ?: "",
                    isReady = azhang.isReady,
                )
                TurnResult(
                    answer = body.reply,
                    ok = body.ok,
                    payload = encodePanelCard(body.card),
                )
            }
            return
        }

        // 取不到单例（非 Hilt 宿主，理论上不会发生）：退回改动前的组合作用域路径。
        // 功能与改动前完全一致，只是切走会断——宁可用旧行为也不能让面板打不开。
        busy = true
        turnStart = SystemClock.elapsedRealtime()
        status = PanelStatus.PREPARING
        scope.launch {
            var finish: AzhangTurn? = null
            val ok = withTimeoutOrNull(PANEL_TURN_BUDGET_MS) {
                azhang.streamTurn(text).collect { ev ->
                    when (ev) {
                        is AzhangStream.Preparing -> status = PanelStatus.PREPARING

                        // 思考素材：只喂折叠区（内部走 ThinkingThrottle，≥150ms 才刷一次），
                        // 面板其余部分不随生成节奏重组。
                        // 收到第一块才把折叠区挂出来 —— 纯准备阶段头部/正文区的
                        // ModelProgressLine 已经在说进度，那时再摆一行「思考中」是重复的。
                        is AzhangStream.Thinking -> {
                            thinkingOn = true
                            thinking.append(ev.delta, SystemClock.elapsedRealtime())
                        }

                        is AzhangStream.Text -> {
                            status = PanelStatus.STREAMING
                            reply += ev.delta
                        }
                        is AzhangStream.Finish -> finish = ev.turn
                    }
                }
                true
            } == true

            val body = panelTurnBody(
                finished = finish,
                streamed = ok,
                draft = reply,
                isReady = azhang.isReady,
            )
            reply = body.reply
            entry = body.card
            status = if (body.ok) PanelStatus.DONE else PanelStatus.FAILED
            // 收尾（成功、超时、失败都算）：强制发布最后几个被节流掉的字，
            // 摘要换成「查看思考过程」；用户中途展开过的话收回去——正文才是主角。
            thinking.finish(SystemClock.elapsedRealtime())
            thinking.collapse()
            busy = false
            turnStart = null
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
                // 等待态一律「阶段 + 已用时」（阶段来自引擎真实状态），
                // 不再是一句没有计时的「模型准备中…」。预期提示放在下面的回答区，那里才宽敞。
                when (status) {
                    PanelStatus.PREPARING, PanelStatus.STREAMING -> ModelProgressLine(
                        phase = if (status == PanelStatus.STREAMING) UiPhase.GENERATING else enginePhase,
                        startedAtMs = turnStart,
                        showHint = false,
                        color = p.accent,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    else -> Text(
                        when (status) {
                            PanelStatus.DONE ->
                                if (entry != null) "识别到账目，确认后可入账" else "本机模型 · 离线可用"

                            else -> "这次没答上"
                        },
                        style = TextStyle(fontSize = 10.5.sp, letterSpacing = .5.sp),
                        color = if (status == PanelStatus.FAILED) p.warn else p.ink3,
                    )
                }
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

        /* 思考过程：默认收起一行摘要，点开是**局部滚动区**（组件内固定最大高度），
         * 不参与面板布局——正文与输入框不会被它顶来顶去。
         * 内容只在 ThinkingSection 内部读，模型每吐一块只重组它自己。 */
        if (thinkingOn) {
            // 组件的字号取 LocalTextStyle；面板整体是 13sp 的小字，给它配一份等身的
            CompositionLocalProvider(
                LocalTextStyle provides TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
            ) {
                ThinkingSection(
                    state = thinking,
                    color = p.ink3,
                    maxHeight = 120.dp,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        /* 回答区：等待阶段 + 已用时 + 流式正文（生成中逐字上屏，不等终态） */
        if (reply.isNotEmpty() || status == PanelStatus.PREPARING || status == PanelStatus.STREAMING) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(p.radiusL))
                    .background(p.bg)
                    .padding(12.dp),
            ) {
                Column {
                    if (status == PanelStatus.PREPARING || status == PanelStatus.STREAMING) {
                        // 首次等待的那句提示放这里：正文区是用户真正盯着的地方
                        ModelProgressLine(
                            phase = if (status == PanelStatus.STREAMING) UiPhase.GENERATING else enginePhase,
                            startedAtMs = turnStart,
                            color = p.ink2,
                        )
                        if (reply.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    }
                    if (reply.isNotEmpty()) {
                        Text(
                            text = reply,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp),
                            color = p.ink,
                        )
                    }
                }
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

/**
 * 一轮的终局 → 面板该显示什么。**纯函数**，单测直接钉（不碰 Compose）。
 *
 * 为什么把这段从协程体里抽出来：正文与卡片现在有**两个来路**——
 * 页面活着时走订阅、进程被杀重来时走磁盘播回。判定规则收在这一个函数里，
 * 两条路才不会各说各话（同一条回答，切之前显示卡片、切回来只剩文字，
 * 是最难复现的那类 bug）。
 *
 * [draft] 一律传**订阅里的草稿**，不在这里自己拼字符串：草稿的真源只有一处
 * （见 [startTurn] 的注释），两处各拼一份迟早会漂。
 *
 * 三条口径与改动前逐字相同：
 *  - 超时留半截：一个字都没吐才算失败，吐了就把已经显示的留着；
 *  - 超时文案分清「还在加载」和「真出不来」——首次冷启动解码就要 100s 上下；
 *  - 失败必须显示**真实原因**，不再挂一句与现场无关的兜底话术。
 */
internal fun panelTurnBody(
    finished: AzhangTurn?,
    streamed: Boolean,
    draft: String,
    isReady: Boolean,
): PanelTurnBody = when {
    !streamed -> PanelTurnBody(
        reply = when {
            draft.isNotEmpty() -> "$draft\n\n—— $TIMEOUT_TEXT"
            isReady -> TIMEOUT_TEXT
            else -> PREPARING_TIMEOUT_TEXT
        },
        // 超时但已经吐了字：算「有内容可看」。没有卡片，也不算失败态。
        card = null,
        ok = draft.isNotEmpty(),
    )

    finished is AzhangTurn.Entry -> PanelTurnBody(
        reply = draft,
        card = finished.entries.firstOrNull(),
        ok = true,
    )

    finished is AzhangTurn.Reply -> PanelTurnBody(
        // 模型认领「查账」→ 不编数字，换成能直接照做的引导。
        reply = if (finished.isLedgerQuery) QUERY_GUIDE else finished.body,
        // 模型只回了话、由本地规则补出来的卡片，照样出 —— 见 withRulesEntryFallback。
        card = finished.entries.firstOrNull(),
        ok = true,
    )

    else -> {
        val reason = (finished as? AzhangTurn.Unavailable)
            ?.reason?.takeIf { it.isNotBlank() } ?: FAILED_TEXT
        PanelTurnBody(
            reply = if (draft.isEmpty()) reason else "$draft\n\n—— $reason",
            card = null,
            ok = false,
        )
    }
}

/**
 * 一轮终局的产物。[ok] = false 时 [reply] 是真实失败原因——界面照样要显示。
 * [card] 是那张能点「记下来」的卡片，没有就 null。
 */
internal data class PanelTurnBody(
    val reply: String,
    val card: dev.dzsun.bookkeeping.feature.entry.ParsedEntry?,
    val ok: Boolean,
)

/**
 * 卡片落盘的形状。**只存恢复一张卡所必需的四个字段**，不存 id/confidence——
 * 那两样在恢复时没有意义（卡已经不是刚生成的了），而 [saveQuickEntry] 真正用到的
 * 就是 amountText / categoryName / note 这三样，kind 用于展示口径。
 *
 * 走 kotlinx.serialization 而不是自拼分隔符：备注是模型给的自由文本，
 * 里面出现 `|` 完全可能（「拿铁|大杯」），自拼分隔符会被劈错列——
 * 那等于把用户的备注切成两半，比不恢复更糟。
 */
@kotlinx.serialization.Serializable
private data class PanelCardWire(
    val amountText: String,
    val categoryName: String,
    val note: String,
    val kind: dev.dzsun.bookkeeping.feature.entry.EntryKind,
)

private val panelCardJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * 卡片 → 随回答一起落盘的那串文本。没有卡片返回 **null**（不是空串——
 * 空串会被解成「有一张卡但字段全空」）。
 */
internal fun encodePanelCard(entry: dev.dzsun.bookkeeping.feature.entry.ParsedEntry?): String? {
    val e = entry ?: return null
    return runCatching {
        panelCardJson.encodeToString(
            PanelCardWire(e.amountText, e.categoryName, e.note, e.kind),
        )
    }.getOrNull()
}

/**
 * 那串文本 → 卡片。**解不出来一律 null**：界面只显示文字，
 * 不显示一张点不动的空卡片。金额或分类为空也作废——一张没有金额的卡对用户没有意义。
 */
internal fun decodePanelCard(payload: String?): dev.dzsun.bookkeeping.feature.entry.ParsedEntry? {
    val p = payload ?: return null
    val wire = runCatching {
        panelCardJson.decodeFromString<PanelCardWire>(p)
    }.getOrNull() ?: return null
    if (wire.amountText.isBlank() || wire.categoryName.isBlank()) return null
    return dev.dzsun.bookkeeping.feature.entry.ParsedEntry(
        id = "restored",
        kind = wire.kind,
        amountText = wire.amountText,
        categoryName = wire.categoryName,
        payee = "",
        note = wire.note,
        // 恢复出来的卡**不继承**原置信度：那张卡不是刚生成的了，
        // 拿 0.98 去点亮「高置信」的样式是在替用户做一个已经过期了的判断。
        confidence = 1f,
    )
}

/** 模型认领「查账」时给的引导 —— 它不编数字，所以要给一句能直接照做的话。 */
private const val QUERY_GUIDE =
    "这个得看你的真实账本，我不编数字。\n点下面「去问账页」问：「这个月餐饮花了多少」。"

/** 兜底失败文案 —— 仅当连具体原因都没拿到（如 turn 为空）时才用，通常显示的是 Unavailable.reason。 */
private const val FAILED_TEXT =
    "这次没答上来。\n可以点「重试」再发一次，或点下面的入口去对话页。"

/**
 * 整轮超过预算、且一个字都没吐出来时的原因文案（秒数取自统一预算）。
 * 这是**已经加载完、纯生成**超时的口径。
 */
internal val TIMEOUT_TEXT =
    "这次生成超时了（超过 ${SparkSession.GEN_BUDGET_MS / 1000} 秒没出结果）。\n" +
        "可以点「重试」再发一次，或点下面的入口去对话页。"

/**
 * 同样是超时，但模型**还在初始化**（首次冷启动系统提示解码实测 97~100s）——
 * 必须和上面那句分开：这不是「答不上来」，是「还在准备」。
 */
internal const val PREPARING_TIMEOUT_TEXT =
    "模型还在准备（首次约 1~2 分钟），这次没等到它就绪。\n" +
        "可以点「重试」再发一次，或点下面的入口去对话页。"

/**
 * 面板一整条链（初始化 + 生成）的总预算。
 * 两个分段预算的唯一定义处在 [SparkSession.INIT_BUDGET_MS] / [SparkSession.GEN_BUDGET_MS]，
 * 这里只做相加 —— 不许再出现第四个写死的数字。
 */
internal const val PANEL_TURN_BUDGET_MS =
    SparkSession.INIT_BUDGET_MS + SparkSession.GEN_BUDGET_MS
