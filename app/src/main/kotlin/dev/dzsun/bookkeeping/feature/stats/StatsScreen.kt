package dev.dzsun.bookkeeping.feature.stats

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.BrandBlue
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import dev.dzsun.bookkeeping.core.money.Money
import kotlin.math.abs

/**
 * 收支报表：AI 小结置顶 → 四宫格数据 → 支出趋势（生长动画）→ 分类占比。
 * 结构对齐参考图，聚合数据全部来自 SQL 投影。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onAskClick: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("收支报表", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    // 作为压栈子页面（报表页「详细统计 →」）时给一个返回键。
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    // 订阅设置：暂时隐藏（未实现的功能不展示）
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 周期切换
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(
                            selected = state.period == p,
                            onClick = { viewModel.onPeriodChange(p) },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            item { AiSummaryCard(state, onAskClick) }
            item { StatsGrid(state) }
            item { TrendCard(state) }
            item { CategoryCard(state) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AiSummaryCard(state: StatsUiState, onAskClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFEDEBFF), Color(0xFFF6F5FF)),
                    ),
                )
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandBlue, Color(0xFF7C5CFF)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("AI 月度小结", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            when (val summary = state.summary) {
                AiSummaryState.Loading -> SummarySkeleton()
                is AiSummaryState.Ready -> {
                    Text(
                        summary.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    )
                    if (!summary.fromAi) {
                        // 本地模板拼的句子不许冒充模型分析：说清楚这是降级内容，
                        // 用户才知道「换台设备/换个网络也许能看到更好的」。
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "暂时连不上 AI，以上为本地小结",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onAskClick() }
                    .padding(top = 4.dp),
            ) {
                Text("查看 AI 账单分析", color = BrandBlue, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 等 AI 分析时的骨架屏。
 *
 * 三行长短不一的灰条，透明度来回呼吸——比一个转圈更能说明「这里马上会有一段话」，
 * 也避免了文字从无到有时整张卡片的高度跳变。
 */
@Composable
private fun SummarySkeleton() {
    val transition = rememberInfiniteTransition(label = "summarySkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "summarySkeletonAlpha",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1f, 0.9f, 0.55f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(BrandBlue.copy(alpha = alpha * 0.22f)),
            )
        }
    }
}

@Composable
private fun StatsGrid(state: StatsUiState) {
    val currency = state.currency
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("本期支出(元)", Money.of(state.expenseMinor, currency).toPlainString(), Modifier.weight(1f))
                StatCell(
                    "日均支出(元)",
                    // 除数与 AI 小结共用 state.periodDays：两处各除各的，屏幕上就会
                    // 出现两个互相矛盾的日均，而用户没有理由知道该信哪个
                    Money.of(state.expenseMinor / state.periodDays, currency).toPlainString(),
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    "比上月支出(元)",
                    "−" + Money.of(abs(state.expenseMinor - state.incomeMinor), currency).toPlainString(),
                    Modifier.weight(1f),
                )
                StatCell(
                    "收支结余(元)",
                    Money.of(abs(state.balanceMinor), currency).toPlainString(),
                    Modifier.weight(1f),
                    valueColor = if (state.balanceMinor >= 0) IncomeGreen else MaterialTheme.colorScheme.error,
                    prefix = if (state.balanceMinor >= 0) "+" else "−",
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    prefix: String = "",
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            prefix + value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

/** 趋势图：进入时线条从左往右生长。 */
@Composable
private fun TrendCard(state: StatsUiState) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(900),
        label = "trendGrow",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("支出趋势", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val bars = state.monthlyBars
            if (bars.isEmpty()) {
                Text(
                    "记几笔就有趋势了",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val max = (bars.maxOf { it.expenseMinor }.toFloat()).coerceAtLeast(1f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val step = if (bars.size > 1) w / (bars.size - 1) else w
                    val path = Path()
                    bars.forEachIndexed { i, bar ->
                        val x = if (bars.size > 1) step * i else w / 2
                        val y = h - (bar.expenseMinor / max) * h * progress
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = BrandBlue,
                        style = Stroke(width = 5f, cap = StrokeCap.Round),
                    )
                    // 面积填充
                    val area = Path().apply {
                        addPath(path)
                        lineTo(if (bars.size > 1) step * (bars.size - 1) else w / 2, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(
                        path = area,
                        brush = Brush.verticalGradient(
                            listOf(BrandBlue.copy(alpha = 0.25f), BrandBlue.copy(alpha = 0f)),
                        ),
                    )
                    bars.forEachIndexed { i, bar ->
                        val x = if (bars.size > 1) step * i else w / 2
                        val y = h - (bar.expenseMinor / max) * h * progress
                        drawCircle(color = BrandBlue, radius = 6f, center = Offset(x, y))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    bars.forEach {
                        Text(
                            "${it.yearMonth.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(state: StatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("支出分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (state.expenseByCategory.isEmpty()) {
                Text("暂无分类数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.expenseByCategory.forEach { slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(sliceColor(slice.name)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(slice.name, modifier = Modifier.weight(1f))
                        Text(
                            Money.of(slice.amountMinor, state.currency).toPlainString(),
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(slice.ratio * 100).toInt()}%",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(slice.ratio.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(sliceColor(slice.name)),
                        )
                    }
                }
            }
        }
    }
}

private fun sliceColor(name: String): Color = when {
    name.contains("餐") -> Color(0xFFFF8A3D)
    name.contains("交通") -> Color(0xFF3DBE9B)
    name.contains("购物") -> Color(0xFFFFB020)
    name.contains("居住") -> Color(0xFF7B8CFF)
    name.contains("娱乐") -> Color(0xFFB56BFF)
    name.contains("医疗") -> Color(0xFFFF6B81)
    name.contains("教育") -> Color(0xFF2BB3C0)
    name.contains("通讯") -> Color(0xFF5B9DFF)
    else -> Color(0xFF9AA3B2)
}
