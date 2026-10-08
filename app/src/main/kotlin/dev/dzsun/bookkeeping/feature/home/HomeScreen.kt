package dev.dzsun.bookkeeping.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.designsystem.*
import dev.dzsun.bookkeeping.feature.ledger.LedgerViewModel
import java.time.LocalDate

/**
 * ============================================================
 *  首页 · Agent 控制台（集成版）
 *  数据源：LedgerViewModel（原「记账」Tab 的 ViewModel，直接复用）
 *   - 数字主舞台 ← uiState.balanceMinor / monthIncome / monthExpense
 *   - 预算执行   ← uiState.budgetUsedFraction（用户自设的月预算）
 *   - 近期流水   ← uiState.entries（点击进凭证详情）
 *   - Top 类目   ← 由 entries 客户端聚合
 *  **条件渲染**：新用户（无账目）只看问候 + 结余 + 三个上手入口；
 *  财务体质在评分模型落地前一律不渲染（见 [HealthBlock]）。
 * ============================================================
 */

/** 首页模块开关（TODO 持久化到 DataStore） */
object HomeModulesState {
    var greeting by mutableStateOf(true)
    var insights by mutableStateOf(true)
    var overview by mutableStateOf(true)
    var transactions by mutableStateOf(true)
    var health by mutableStateOf(true)
}

/**
 * 给出建议所需最少账目笔数。
 *
 * 依据：10 笔通常已经跨过若干分类与日期，能看出「钱主要花在哪」；
 * 只按天数不够——连续 7 天各记一笔，仍可能全是同一分类，看不出结构。
 * 阈值宁可偏保守：少给建议好过编一条假建议。
 */
private const val ADVICE_MIN_ENTRIES = 10

@Composable
fun HomeScreen(
    onOpenChat: () -> Unit = {},
    onEntryClick: (String) -> Unit = {},
    onAddEntry: () -> Unit = {},
    onImportClick: () -> Unit = {},
    onAskClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLedger: () -> Unit = {},
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // 数据存在性判据：只用 uiState 已有字段，不另起全局状态。
    val hasAnyLedger = ui.entries.isNotEmpty()
    val enoughForAdvice = ui.entries.size >= ADVICE_MIN_ENTRIES

    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Art.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        if (HomeModulesState.greeting) HeroBlock(onOpenChat)
        StageBlock(
            balanceMinor = ui.balanceMinor,
            incomeMinor = ui.monthIncomeMinor,
            expenseMinor = ui.monthExpenseMinor,
            monthLabel = ui.monthLabel,
        )
        if (!hasAnyLedger) {
            // 新用户：三个真实可点的入口。不渲染任何需要历史数据的模块——
            // 空的「本月总览 / 近期流水」占半屏没有意义，编出来的建议更糟。
            OnboardingRail(onAddEntry, onImportClick, onOpenChat)
            Spacer(Modifier.height(40.dp))
        } else {
            if (HomeModulesState.insights) {
                SectionHeader(1, "主动建议", vertical = "建言三则") { /* TODO 全部建言 */ }
                InsightRail(
                    hasEnoughData = enoughForAdvice,
                    entryCount = ui.entries.size,
                    onAddEntry = onAddEntry,
                )
                Spacer(Modifier.height(40.dp))
            }
            if (HomeModulesState.overview) {
                SectionHeader(2, "本月总览", vertical = "收支总览")
                OverviewBlock(ui.monthIncomeMinor, ui.monthExpenseMinor, ui.budgetUsedFraction, ui.budgetMinor, ui.budgetRemainingMinor, ui.entries, onOpenSettings)
                Spacer(Modifier.height(40.dp))
            }
            if (HomeModulesState.transactions) {
                SectionHeader(3, "近期流水", vertical = "今日账目", onMore = onOpenLedger)
                TxList(ui.entries.take(5), onEntryClick)
                Spacer(Modifier.height(40.dp))
            }
        }
        // 财务体质：评分模型还没做（见 HealthBlock 的 TODO），需要预算 + 多月历史
        // 才撑得起一个分数。在那之前一律不渲染——一个编出来的 82 分比没有更伤信任。
        // 评分模型接入后把 healthReady 翻成 true，并按与 insights 相同的判据放开。
        val healthReady = false
        if (HomeModulesState.health && healthReady) {
            SectionHeader(4, "财务体质", vertical = "体质评分")
            HealthBlock()
        }
        ColophonBlock()
        Spacer(Modifier.height(110.dp))
    }

        AiOrb(
            onClick = onAskClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 92.dp),
        )
    }
}

/**
 * 首页 AI 悬浮球：右下角，指向问账页。
 *
 * 原在旧「记账」页（LedgerScreen）上，四 Tab 重构后那条路由被删、球也跟着没了。
 * 首页是新设计里的主入口，球长在这里才够得着。
 */
@Composable
private fun AiOrb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Art.colors
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (p.dark) p.accent else p.ink)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "AI",
            color = if (p.dark) Color(0xFF131109) else p.bg,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 1.sp,
        )
    }
}

/* ---------------- 金额工具 ---------------- */

private fun fmtInt(minor: Long): String = "%,d".format(kotlin.math.abs(minor) / 100)
private fun fmtDec(minor: Long): String = ".%02d".format(kotlin.math.abs(minor) % 100)
private fun fmtFull(minor: Long): String = "%,.2f".format(kotlin.math.abs(minor) / 100.0)

private val zhWeek = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

/* ---------------- 问候刊头 ---------------- */

@Composable
private fun HeroBlock(onOpenChat: () -> Unit) {
    val p = Art.colors
    val t = Art.type
    val today = LocalDate.now()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(1.dp).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text(
            "${today.monthValue} 月 ${today.dayOfMonth} 日 · ${zhWeek[today.dayOfWeek.value - 1]}",
            style = TextStyle(fontSize = 11.5.sp, letterSpacing = 3.sp, fontFamily = t.body),
            color = if (p.dark) p.accent else p.ink2,
        )
    }
    Spacer(Modifier.height(16.dp))
    val greeting = when (java.time.LocalTime.now().hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    Text(
        buildAnnotatedString {
            append("$greeting，")
            // TODO 接用户资料中的称呼
            if (t.greetingItalic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("念安") }
            else withStyle(SpanStyle(color = p.accent)) { append("念安") }
        },
        style = TextStyle(fontFamily = t.display, fontWeight = t.heroWeight, fontSize = 40.sp, lineHeight = 52.sp, letterSpacing = 1.sp),
        color = p.ink,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "我是阿账，你的账房管家。想记账、查账，随时开口。",
        style = TextStyle(fontSize = 14.5.sp, lineHeight = 24.sp, fontFamily = t.body),
        color = p.ink2,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        "和阿账聊聊 →",
        style = TextStyle(fontSize = 13.sp, letterSpacing = 1.5.sp, fontFamily = t.body),
        color = p.accent,
        modifier = Modifier.clickable(onClick = onOpenChat),
    )
    Spacer(Modifier.height(14.dp))
}

/* ---------------- 数字主舞台 ---------------- */

@Composable
private fun StageBlock(balanceMinor: Long, incomeMinor: Long, expenseMinor: Long, monthLabel: String) {
    val p = Art.colors
    val inner: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .rotate(if (p.dark) 45f else 0f)
                    .then(
                        if (p.dark) Modifier.border(1.dp, p.accent, RoundedCornerShape(0.dp))
                        else Modifier.background(p.accent, RoundedCornerShape(1.dp))
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "本月结余 · $monthLabel",
                style = TextStyle(fontSize = 12.sp, letterSpacing = 3.sp, fontFamily = Art.type.body),
                color = p.ink2,
            )
        }
        Spacer(Modifier.height(12.dp))
        AmountText(integer = fmtInt(balanceMinor), decimal = fmtDec(balanceMinor), fontSize = 58)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                buildAnnotatedString {
                    append("入 ")
                    withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append("¥${fmtFull(incomeMinor)}") }
                },
                style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.body), color = p.ink2,
            )
            Text(
                buildAnnotatedString {
                    append("出 ")
                    withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append("¥${fmtFull(expenseMinor)}") }
                },
                style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.body), color = p.ink2,
            )
        }
    }

    if (p.stageAsCard) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(p.radiusL)).background(p.surface).padding(26.dp)) { inner() }
    } else {
        Column {
            Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 30.dp)) { inner() }
            Hairline()
        }
    }
    Spacer(Modifier.height(44.dp))
}

/* ---------------- 主动建议横滑 ---------------- */

/**
 * 三态里的后两态（第一态「无账目」整块不渲染，由 [HomeScreen] 处理）：
 * - 数据还不够 → 引导态卡片，文案只说「还差几笔」，不编任何金额/百分比，
 *   更不出现「超支」「预警」——没有预算就不该有超支；
 * - 数据够了   → [demoInsights]。那是**占位文案**（不含编造数字），
 *   等 feature/discover 的 Advisor 接上再换成真建议。
 */
@Composable
private fun InsightRail(hasEnoughData: Boolean, entryCount: Int, onAddEntry: () -> Unit) {
    val items: List<AgentInsight>
    val actions: List<() -> Unit>
    if (hasEnoughData) {
        items = demoInsights
        actions = List(items.size) { {} }
    } else {
        val missing = (ADVICE_MIN_ENTRIES - entryCount).coerceAtLeast(1)
        items = listOf(
            AgentInsight(
                InsightType.INFO, "还在熟悉你的花销",
                "再记 $missing 笔，我帮你找出花钱规律。现在先把每一笔记下来就好。",
                "记一笔 →",
            ),
        )
        actions = listOf(onAddEntry)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 40.dp)) {
        itemsIndexed(items) { i, insight -> InsightCard(insight = insight, index = i, onAction = actions[i]) }
    }
}

/* ---------------- 新用户上手入口 ---------------- */

/**
 * 新用户首屏的三个真实入口。版式复用 [InsightCard] 的卡片容器（宽度/圆角/内边距
 * 与主动建议横滑一致），只换内容——不引入第二套设计语言。
 *
 * 三条入口都直接接到已有能力：记一笔（底部 +）、导入账单（ImportScreen）、问一句（Chat）。
 */
@Composable
private fun OnboardingRail(
    onAddEntry: () -> Unit,
    onImportClick: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val p = Art.colors
    Text(
        "从 这 里 开 始",
        style = TextStyle(fontSize = 12.sp, letterSpacing = 2.sp, fontFamily = Art.type.body),
        color = p.ink3,
    )
    Spacer(Modifier.height(14.dp))
    val items = listOf(
        AgentInsight(
            InsightType.INVEST, "记第一笔",
            "记第一笔，这里就有变化。说一句「午饭 35」也行。",
            "去记一笔 →",
        ),
        AgentInsight(
            InsightType.INFO, "导入账单",
            "微信、支付宝的账单可以直接导进来，不用一笔笔补。",
            "去导入 →",
        ),
        AgentInsight(
            InsightType.INFO, "问一句",
            "试试问：这个月能花多少。",
            "和阿账聊聊 →",
        ),
    )
    val actions = listOf(onAddEntry, onImportClick, onOpenChat)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 40.dp)) {
        itemsIndexed(items) { i, insight -> InsightCard(insight = insight, index = i, onAction = actions[i]) }
    }
}

/* ---------------- 本月总览 ---------------- */

@Composable
private fun OverviewBlock(
    incomeMinor: Long, expenseMinor: Long,
    budgetFraction: Float, budgetMinor: Long, budgetRemainingMinor: Long,
    entries: List<LedgerRow>,
    onOpenSettings: () -> Unit,
) {
    val p = Art.colors
    if (p.stageAsCard) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OvItem("收入 · INCOME", incomeMinor, Modifier.weight(1f))
            OvItem("支出 · EXPENSE", expenseMinor, Modifier.weight(1f))
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            OvItem("收入 · INCOME", incomeMinor, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(92.dp).background(p.line))
            Spacer(Modifier.width(28.dp))
            OvItem("支出 · EXPENSE", expenseMinor, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(30.dp))
    if (budgetMinor <= 0L) {
        // 没设预算：不显示 0% / ¥0 / 尚可花——那会让用户以为「预算执行 0%」是有意义的。
        // 更不出「超支」字样：没有预算就不该有超支。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "未设月预算",
                style = TextStyle(fontSize = 12.5.sp, fontFamily = Art.type.body), color = p.ink3,
            )
            Text(
                "去设置里定一个 →",
                style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp, fontFamily = Art.type.body), color = p.accent,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                buildAnnotatedString {
                    append("预算执行 ")
                    withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append("${(budgetFraction * 100).toInt()}%") }
                },
                style = TextStyle(fontSize = 12.5.sp, fontFamily = Art.type.body), color = p.ink2,
            )
            Text(
                buildAnnotatedString {
                    append("月预算 ")
                    withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append("¥${fmtFull(budgetMinor)}") }
                },
                style = TextStyle(fontSize = 12.5.sp, fontFamily = Art.type.body), color = p.ink2,
            )
        }
        Spacer(Modifier.height(10.dp))
        ThinBar(fraction = budgetFraction, color = if (budgetFraction > 0.85f) p.warn else p.accent, thick = if (p.stageAsCard) 6.dp else 2.dp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已用 ¥${fmtFull(expenseMinor)}", style = TextStyle(fontSize = 11.5.sp, letterSpacing = 0.5.sp), color = p.ink3)
            Text("尚可花 ¥${fmtFull(budgetRemainingMinor)}", style = TextStyle(fontSize = 11.5.sp, letterSpacing = 0.5.sp), color = p.ink3)
        }
    }
    Spacer(Modifier.height(28.dp))
    // Top3 支出类目：客户端聚合 entries
    val topCats = entries
        .filter { it.categoryType == AccountType.EXPENSE }
        .groupBy { it.categoryName }
        .map { (name, rows) -> name to rows.sumOf { kotlin.math.abs(it.amountMinor) } }
        .sortedByDescending { it.second }
        .take(3)
    if (topCats.isNotEmpty()) {
        val total = topCats.sumOf { it.second }.coerceAtLeast(1)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            topCats.forEachIndexed { i, (name, amt) ->
                CatItem(name, "¥${fmtInt(amt)}", "占支出 ${(amt * 100 / total)}%", p.chart[i % p.chart.size], Modifier.weight(1f))
            }
            repeat(3 - topCats.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun OvItem(label: String, minor: Long, modifier: Modifier) {
    val p = Art.colors
    val content: @Composable ColumnScope.() -> Unit = {
        Text(label, style = TextStyle(fontSize = 11.5.sp, letterSpacing = 2.5.sp, fontFamily = Art.type.body), color = p.ink2)
        Spacer(Modifier.height(10.dp))
        AmountText(integer = fmtInt(minor), decimal = fmtDec(minor), fontSize = 32)
    }
    if (p.stageAsCard) {
        Column(modifier.clip(RoundedCornerShape(p.radiusL)).background(p.surface).padding(22.dp)) { content() }
    } else {
        Column(modifier) { content() }
    }
}

@Composable
private fun CatItem(name: String, value: String, pct: String, color: Color, modifier: Modifier) {
    val p = Art.colors
    Column(modifier) {
        if (!p.stageAsCard) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).rotate(if (p.dark) 45f else 0f).background(color, RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(8.dp))
            Text(name, style = TextStyle(fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = Art.type.body), color = p.ink2)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = TextStyle(fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontSize = 20.sp, fontFeatureSettings = "tnum"), color = p.ink)
        Text(pct, style = TextStyle(fontSize = 11.sp, fontFamily = Art.type.body), color = p.ink3)
    }
}

/* ---------------- 近期流水（真实数据） ---------------- */

@Composable
private fun TxList(items: List<LedgerRow>, onEntryClick: (String) -> Unit) {
    val p = Art.colors
    val today = LocalDate.now().toEpochDay()
    Column {
        Hairline()
        if (items.isEmpty()) {
            Text(
                "还没有账目。点中央 ＋，或和阿账说一句「午饭 35」。",
                modifier = Modifier.padding(vertical = 28.dp),
                style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.body),
                color = p.ink3,
            )
        }
        items.forEach { row ->
            val income = row.categoryType == AccountType.INCOME
            // 约定：支出分录恒为正，收入恒为负（见 LedgerViewModel）
            val displayMinor = if (income) -row.amountMinor else row.amountMinor
            val c = if (income) p.pos else p.chart[row.categoryName.hashCode().mod(p.chart.size)]
            val date = LocalDate.ofEpochDay(row.dateEpochDay)
            val dateLabel = if (row.dateEpochDay == today) "今天" else "${date.monthValue}月${date.dayOfMonth}日"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntryClick(row.journalId) }
                    .padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .then(
                            if (p.dark) Modifier.rotate(45f).border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(p.radius))
                            else Modifier.clip(RoundedCornerShape(if (p.stageAsCard) 11.dp else p.radius)).background(c.copy(alpha = 0.12f))
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        row.categoryName.take(1),
                        modifier = Modifier.rotate(if (p.dark) -45f else 0f),
                        style = TextStyle(fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        color = c,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.payee ?: row.note ?: row.categoryName,
                        style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body),
                        color = p.ink,
                    )
                    Text(
                        "$dateLabel · ${row.categoryName}" + (row.place?.let { " · $it" } ?: ""),
                        style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.body),
                        color = p.ink3,
                    )
                }
                Text(
                    (if (income) "＋ " else "− ") + fmtFull(displayMinor),
                    style = TextStyle(fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontSize = 16.5.sp, fontFeatureSettings = "tnum"),
                    color = if (income) p.pos else p.ink,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
        }
    }
}

/* ---------------- 财务体质（暂不渲染，等评分模型） ---------------- */

/**
 * 评分模型还没做，这里的 82 / 储蓄率 / … **全是写死的假数**。
 * 在评分模型接入之前 [HomeScreen] 一律不调用本函数（含章节刊头）——
 * 一个编出来的分数比空白更伤信任。模型落地后按
 * `hasAnyLedger && enoughForAdvice` 的同一判据放开，函数体与样式保持不动。
 */
@Composable
private fun HealthBlock() {
    val p = Art.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                val padPx = 8.dp.toPx()
                val arcSize = Size(size.width - padPx * 2, size.height - padPx * 2)
                val topLeft = Offset(padPx, padPx)
                drawArc(p.line2, 0f, 360f, false, topLeft, arcSize, style = stroke)
                drawArc(p.accent, -90f, 360f * 0.82f, false, topLeft, arcSize, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("82", style = TextStyle(fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontSize = 46.sp, fontFeatureSettings = "tnum"), color = p.ink)
                Text("体质分", style = TextStyle(fontSize = 10.5.sp, letterSpacing = 3.sp), color = p.ink3)
            }
        }
        Spacer(Modifier.width(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DimBar("储蓄率", 88, p.chart[1])
            DimBar("预算执行", 74, p.chart[0])
            DimBar("负债健康", 90, p.chart[3])
            DimBar("消费稳定", 76, p.chart[2])
        }
    }
}

@Composable
private fun DimBar(name: String, score: Int, color: Color) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
            Text("$score", style = TextStyle(fontSize = 12.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink2)
        }
        Spacer(Modifier.height(8.dp))
        ThinBar(fraction = score / 100f, color = color, thick = if (p.stageAsCard) 5.dp else 2.dp)
    }
}

@Composable
private fun ThinBar(fraction: Float, color: Color, thick: Dp) {
    Box(Modifier.fillMaxWidth().height(thick).clip(RoundedCornerShape(999.dp)).background(Art.colors.line2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(color))
    }
}

/* ---------------- 落款 ---------------- */

@Composable
private fun ColophonBlock() {
    Column(Modifier.fillMaxWidth().padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (Art.style != ArtStyle.MINIMAL) {
            SealBadge(char = "账", size = 30.dp)
            Spacer(Modifier.height(12.dp))
        }
        Text("账房 · 与你共记", style = TextStyle(fontSize = 11.5.sp, letterSpacing = 4.sp, fontFamily = Art.type.body), color = Art.colors.ink3)
    }
}
