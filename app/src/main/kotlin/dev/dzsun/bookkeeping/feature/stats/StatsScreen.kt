package dev.dzsun.bookkeeping.feature.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.money.Money

// 分类配色板：足够 8 类，再多就循环
private val sliceColors = listOf(
    Color(0xFF2456D6),
    Color(0xFF2B6B6B),
    Color(0xFF8B5CF6),
    Color(0xFFE8734A),
    Color(0xFFD4A017),
    Color(0xFF3A9B6E),
    Color(0xFFC2185B),
    Color(0xFF5C6BC0),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("统计") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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

            // 收支总览
            item { SummaryCard(state) }

            // 分类占比
            if (state.expenseByCategory.isNotEmpty()) {
                item { SectionTitle("支出分类") }
                item { PieCard(state) }
                item { CategoryLegend(state) }
            }

            // 月度趋势
            item { SectionTitle("近半年趋势") }
            item { TrendCard(state) }

            // AI 洞察
            item { SectionTitle("AI 洞察") }
            items(state.insights) { insight ->
                InsightCard(insight)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SummaryCard(state: StatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryCell("支出", state.expenseMinor, state.currency, negative = true)
            SummaryCell("收入", state.incomeMinor, state.currency, negative = false)
            SummaryCell("结余", kotlin.math.abs(state.balanceMinor), state.currency, negative = state.balanceMinor < 0)
        }
    }
}

@Composable
private fun SummaryCell(label: String, minor: Long, currency: String, negative: Boolean) {
    val money = Money.of(minor, currency)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (negative) "-${money.format()}" else money.format(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PieCard(state: StatsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            PieChart(
                slices = state.expenseByCategory.map { it.ratio to sliceColor(it.categoryId) },
                modifier = Modifier.size(180.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("总支出", style = MaterialTheme.typography.labelSmall)
                Text(
                    Money.of(state.expenseMinor, state.currency).format(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun sliceColor(categoryId: String): Color {
    val idx = (categoryId.hashCode() and Int.MAX_VALUE) % sliceColors.size
    return sliceColors[idx]
}

@Composable
private fun PieChart(slices: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 36.dp.toPx())
        val inset = stroke.width / 2
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        val topLeft = Offset(inset, inset)
        var startAngle = -90f
        slices.forEach { (ratio, color) ->
            val sweep = ratio * 360f
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                startAngle += sweep
            }
        }
    }
}

@Composable
private fun CategoryLegend(state: StatsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.expenseByCategory.take(6).forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(sliceColor(slice.categoryId), CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(slice.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${(slice.ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Money.of(slice.amountMinor, state.currency).format(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendCard(state: StatsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val max = (state.monthlyBars.maxOfOrNull { maxOf(it.expenseMinor, it.incomeMinor) } ?: 1L)
                .coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                state.monthlyBars.forEach { bar ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(120.dp),
                        ) {
                            Bar(
                                ratio = bar.expenseMinor.toFloat() / max,
                                color = MaterialTheme.colorScheme.primary,
                                label = "支",
                            )
                            Bar(
                                ratio = bar.incomeMinor.toFloat() / max,
                                color = MaterialTheme.colorScheme.secondary,
                                label = "收",
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${bar.yearMonth.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                LegendDot(MaterialTheme.colorScheme.primary, "支出")
                Spacer(Modifier.width(16.dp))
                LegendDot(MaterialTheme.colorScheme.secondary, "收入")
            }
        }
    }
}

@Composable
private fun Bar(ratio: Float, color: Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Canvas(modifier = Modifier.width(12.dp).height((ratio.coerceIn(0.02f, 1f) * 110).dp)) {
            drawRoundRect(
                color = color,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InsightCard(insight: AiInsight) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    insight.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    insight.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
