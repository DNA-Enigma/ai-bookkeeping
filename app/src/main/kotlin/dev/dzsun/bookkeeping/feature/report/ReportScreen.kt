package dev.dzsun.bookkeeping.feature.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.*
import java.time.YearMonth

/**
 * ============================================================
 *  报表页（集成版）
 *  数据源：ReportViewModel（原报表 ViewModel，直接复用）
 *   - 支出构成环图 + 图例 ← report.categories（真实聚合，含预算水位）
 *   - 收入/支出/结余/环比 ← report（MonthlyReport 展示口径已算好）
 *   - 月份切换             ← onPreviousMonth / onNextMonth
 *   - 半年支出趋势         ← trend（ViewModel.observeRecentMonths，近 6 个月真实聚合）
 *   - 收支对比             ← report（本月收入/支出 + 上月支出）
 * ============================================================
 */

private fun fmt(minor: Long): String = "%,.2f".format(kotlin.math.abs(minor) / 100.0)
private fun fmtInt(minor: Long): String = "%,d".format(kotlin.math.abs(minor) / 100)

@Composable
fun ReportScreen(
    onAskClick: () -> Unit = {},
    onOpenDetailStats: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val report = ui.report

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Art.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        ReportHero(ui.month, report, ui.canGoPrevious, ui.canGoNext, viewModel::onPreviousMonth, viewModel::onNextMonth)

        // AI 账单分析入口：一句话问账本（判定交调度层，算术在本机）。
        LinkRow(
            title = "查看 AI 账单分析",
            subtitle = "用一句话问这个月的花销",
            onClick = onAskClick,
        )
        Spacer(Modifier.height(28.dp))

        if (report == null) {
            Text(
                "正在算账……",
                modifier = Modifier.padding(vertical = 48.dp),
                style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
                color = Art.colors.ink3,
            )
        } else {
            SectionHeader(1, "支出构成")
            DonutBlock(report)
            Spacer(Modifier.height(44.dp))
            SectionHeader(2, "半年支出趋势")
            TrendBlock(ui.trend)
            Spacer(Modifier.height(44.dp))
            SectionHeader(3, "收支对比")
            CompareBlock(report)
            Spacer(Modifier.height(44.dp))
            // 详细统计（含 AI 月度小结）在 StatisticsScreen，别在这里重复造一份。
            LinkRow(
                title = "详细统计",
                subtitle = "四宫格数据 + AI 月度小结",
                onClick = onOpenDetailStats,
            )
        }
        Spacer(Modifier.height(110.dp))
    }
}

/** 报表页里的入口行：标题 + 副标题 + 箭头，整行可点。 */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    val p = Art.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(p.radiusL))
            .background(p.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body),
                color = p.ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body),
                color = p.ink3,
            )
        }
        Text(
            "→",
            style = TextStyle(fontSize = 16.sp),
            color = p.accent,
        )
    }
}

@Composable
private fun ReportHero(
    month: YearMonth, report: MonthlyReport?,
    canPrev: Boolean, canNext: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit,
) {
    val p = Art.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(1.dp).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text(
            "报告 · ${month.year} 年 ${month.monthValue} 月",
            style = TextStyle(fontSize = 11.5.sp, letterSpacing = 3.sp, fontFamily = Art.type.body),
            color = if (p.dark) p.accent else p.ink2,
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "收支报告",
            style = TextStyle(fontFamily = Art.type.display, fontWeight = Art.type.heroWeight, fontSize = 38.sp, letterSpacing = 1.sp),
            color = p.ink,
        )
        // 月份切换
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", Modifier.clickable(enabled = canPrev, onClick = onPrev).padding(10.dp),
                style = TextStyle(fontSize = 16.sp), color = if (canPrev) p.ink else p.line)
            Text("→", Modifier.clickable(enabled = canNext, onClick = onNext).padding(10.dp),
                style = TextStyle(fontSize = 16.sp), color = if (canNext) p.ink else p.line)
        }
    }
    Spacer(Modifier.height(10.dp))
    if (report != null) {
        Text(
            buildAnnotatedString {
                append("支出 ")
                withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append("¥${fmt(report.expenseMinor)}") }
                report.changeRatio?.let { ratio ->
                    append("，较上月 ")
                    val pct = "%.1f".format(kotlin.math.abs(ratio) * 100)
                    withStyle(SpanStyle(color = if (ratio > 0) p.warn else p.pos, fontWeight = FontWeight.Medium)) {
                        append(if (ratio > 0) "+$pct%" else "−$pct%")
                    }
                }
                append("。")
            },
            style = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, fontFamily = Art.type.body),
            color = p.ink2,
        )
    }
    Spacer(Modifier.height(36.dp))
}

/* ---------------- 环图 + 图例（真实数据） ---------------- */

@Composable
private fun DonutBlock(report: MonthlyReport) {
    val p = Art.colors
    // 取前 5 类，其余合并为「其他」
    val lines = report.categories
    val shown = lines.take(5)
    val restShare = lines.drop(5).sumOf { it.share.toDouble() }.toFloat()
    val slices = shown.map { it.name to it.share } + listOfNotNull(
        if (restShare > 0.001f) "其他" to restShare else null
    )
    val strokeDp = when (Art.style) {
        ArtStyle.MINIMAL -> 22.dp
        ArtStyle.EDITORIAL -> 14.dp
        else -> 17.dp
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = strokeDp.toPx())
                val padPx = strokeDp.toPx() / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - padPx * 2, size.height - padPx * 2)
                val topLeft = Offset(padPx, padPx)
                var start = -90f
                slices.forEachIndexed { i, (_, share) ->
                    val sweep = share * 360f
                    drawArc(p.chart[i % p.chart.size], start, (sweep - 1.4f).coerceAtLeast(0.5f), false, topLeft, arcSize, style = stroke)
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("总支出", style = TextStyle(fontSize = 10.5.sp, letterSpacing = 3.sp), color = p.ink3)
                Spacer(Modifier.height(6.dp))
                Text("¥${fmtInt(report.expenseMinor)}",
                    style = TextStyle(fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontSize = 26.sp, fontFeatureSettings = "tnum"),
                    color = p.ink)
                Spacer(Modifier.height(4.dp))
                if (report.daysInAverage > 0) {
                    Text("日均 ¥${fmt(report.dailyAverageMinor)}",
                        style = TextStyle(fontSize = 11.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"),
                        color = p.pos)
                }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    Column {
        slices.forEachIndexed { i, (name, share) ->
            if (i == 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            val line = shown.getOrNull(i)
            // 超预算的类目用警示色（预算水位来自 CategoryReportLine.usage）
            val overspent = line?.usage?.let { it > 1f } == true
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(1.dp)).background(p.chart[i % p.chart.size]))
                Spacer(Modifier.width(14.dp))
                Text(name, Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, fontFamily = Art.type.body), color = p.ink)
                if (overspent) {
                    Text("超预算", style = TextStyle(fontSize = 10.5.sp, letterSpacing = 1.sp), color = p.warn)
                    Spacer(Modifier.width(12.dp))
                }
                Text("${(share * 100).toInt()}%", style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink2)
                Spacer(Modifier.width(24.dp))
                Text(
                    line?.let { fmt(it.spentMinor) } ?: "—",
                    style = TextStyle(fontSize = 14.5.sp, fontFamily = Art.type.num, fontWeight = Art.type.numWeight, fontFeatureSettings = "tnum"),
                    color = if (overspent) p.warn else p.ink,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
        }
    }
}

/* ---------------- 半年趋势（近 6 个月真实聚合） ---------------- */

private val monthLabels = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
private fun monthLabel(ym: YearMonth): String = monthLabels[ym.monthValue - 1] + "月"

@Composable
private fun TrendBlock(trend: List<TrendPoint>) {
    val p = Art.colors
    // 空列表＝还算不出来（正在切月），不画任何东西
    if (trend.isEmpty()) return
    if (trend.all { it.expenseMinor == 0L && it.incomeMinor == 0L }) {
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text(
                "记几笔就有趋势了",
                style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
                color = p.ink3,
            )
        }
    } else {
        val maxV = trend.maxOf { it.expenseMinor }.coerceAtLeast(1L)
        Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            trend.forEachIndexed { i, point ->
                val cur = i == trend.lastIndex
                val v = point.expenseMinor
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxHeight()) {
                    Text(if (v == 0L) "0" else fmt(v), style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink2)
                    Spacer(Modifier.height(8.dp))
                    val barW = when (Art.style) { ArtStyle.EDITORIAL -> 10.dp; ArtStyle.LUXE -> 12.dp; else -> 34.dp }
                    Box(
                        Modifier
                            .width(barW)
                            .fillMaxHeight(v.toFloat() / maxV * 0.82f)
                            .clip(RoundedCornerShape(topStart = p.radius, topEnd = p.radius))
                            .background(if (cur) p.accent else p.accent.copy(alpha = 0.45f))
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(monthLabel(point.yearMonth), style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.sp, fontFamily = Art.type.body,
                        fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (cur) p.ink else p.ink3)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
}

/* ---------------- 收支对比（当月真实 + 上月参照） ---------------- */

@Composable
private fun CompareBlock(report: MonthlyReport) {
    val p = Art.colors
    val maxV = maxOf(report.incomeMinor, report.previousExpenseMinor, report.expenseMinor).coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        KeyDot("收入", p.chart[3])
        KeyDot("支出", p.accent)
    }
    Spacer(Modifier.height(20.dp))
    CmpRow("本月", report.incomeMinor, report.expenseMinor, maxV)
    if (report.previousExpenseMinor > 0) {
        CmpRow("上月", null, report.previousExpenseMinor, maxV)
    }
}

@Composable
private fun CmpRow(label: String, incomeMinor: Long?, expenseMinor: Long, maxV: Long) {
    val p = Art.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(52.dp), style = TextStyle(fontSize = 12.5.sp, fontFamily = Art.type.body), color = p.ink2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (incomeMinor != null) CmpBar(incomeMinor.toFloat() / maxV, p.chart[3], fmt(incomeMinor))
            CmpBar(expenseMinor.toFloat() / maxV, p.accent, fmt(expenseMinor))
        }
    }
}

@Composable
private fun KeyDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(Art.colors.radius)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = Art.colors.ink2)
    }
}

@Composable
private fun CmpBar(fraction: Float, color: Color, label: String) {
    val p = Art.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(fraction.coerceIn(0.02f, 1f))
                .height(if (p.stageAsCard) 8.dp else 9.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Spacer(Modifier.weight((1f - fraction).coerceIn(0f, 0.98f) + 0.001f).height(1.dp))
        Text(label, style = TextStyle(fontSize = 11.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink3)
    }
}
