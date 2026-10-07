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
 *  半年趋势与收支对比：MonthlyReportSource 按月查询，
 *  需要 6 个月聚合接口（TODO 在 ViewModel 增 observeRecentMonths），
 *  当前为演示数据。
 * ============================================================
 */

private fun fmt(minor: Long): String = "%,.2f".format(kotlin.math.abs(minor) / 100.0)
private fun fmtInt(minor: Long): String = "%,d".format(kotlin.math.abs(minor) / 100)

@Composable
fun ReportScreen(viewModel: ReportViewModel = hiltViewModel()) {
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
            TrendBlock()
            Spacer(Modifier.height(44.dp))
            SectionHeader(3, "收支对比")
            CompareBlock(report)
        }
        Spacer(Modifier.height(110.dp))
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

/* ---------------- 半年趋势（演示数据，TODO 6 个月聚合接口） ---------------- */

@Composable
private fun TrendBlock() {
    val p = Art.colors
    val demo = listOf("五月" to 9.8f, "六月" to 11.2f, "七月" to 10.5f, "八月" to 12.6f, "九月" to 10.9f, "十月" to 11.5f)
    val maxV = demo.maxOf { it.second }
    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        demo.forEachIndexed { i, (m, v) ->
            val cur = i == demo.lastIndex
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxHeight()) {
                Text("${v}k", style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink2)
                Spacer(Modifier.height(8.dp))
                val barW = when (Art.style) { ArtStyle.EDITORIAL -> 10.dp; ArtStyle.LUXE -> 12.dp; else -> 34.dp }
                Box(
                    Modifier
                        .width(barW)
                        .fillMaxHeight(v / maxV * 0.82f)
                        .clip(RoundedCornerShape(topStart = p.radius, topEnd = p.radius))
                        .background(if (cur) p.accent else p.accent.copy(alpha = 0.45f))
                )
                Spacer(Modifier.height(10.dp))
                Text(m, style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.sp, fontFamily = Art.type.body,
                    fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (cur) p.ink else p.ink3)
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
