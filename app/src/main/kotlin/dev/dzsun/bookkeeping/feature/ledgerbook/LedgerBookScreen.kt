package dev.dzsun.bookkeeping.feature.ledgerbook

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.*
import java.time.YearMonth

/**
 * ============================================================
 *  账簿页 · 复式记账（与 HTML 原型 #pg-ledger 1:1 对应）
 *  科目余额表（双线表头/合计）+ 近期凭证（借/贷分录）
 *
 *  数据源：LedgerBookViewModel（按月 SQL 聚合：科目余额试算 + 凭证明细）
 * ============================================================
 */

private fun fmt(minor: Long): String = "%,.2f".format(kotlin.math.abs(minor) / 100.0)

/** 零金额显示「—」，与会计表格的空位写法一致。 */
private fun fmtOrDash(minor: Long): String = if (minor == 0L) "—" else fmt(minor)

@Composable
fun LedgerBookScreen(viewModel: LedgerBookViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Art.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        LedgerHero(ui.month)
        if (ui.isLoading) {
            Text(
                "正在读账本…",
                modifier = Modifier.padding(vertical = 48.dp),
                style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
                color = Art.colors.ink3,
            )
        } else if (ui.isEmpty) {
            Text(
                "本月还没有分录",
                modifier = Modifier.padding(vertical = 48.dp),
                style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
                color = Art.colors.ink3,
            )
        } else {
            SectionHeader(1, "余额试算")
            BalanceTable(ui)
            Spacer(Modifier.height(44.dp))
            SectionHeader(2, "近期凭证")
            if (ui.vouchers.isEmpty()) {
                Text(
                    "本月还没有分录",
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
                    color = Art.colors.ink3,
                )
            } else {
                ui.vouchers.forEach { VoucherCard(it); Spacer(Modifier.height(14.dp)) }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun LedgerHero(month: YearMonth) {
    val p = Art.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(1.dp).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text(
            "复式账簿 · 借贷记账法",
            style = TextStyle(fontSize = 11.5.sp, letterSpacing = 3.sp, fontFamily = Art.type.body),
            color = if (p.dark) p.accent else p.ink2
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "科目余额表",
            style = TextStyle(
                fontFamily = Art.type.display, fontWeight = Art.type.heroWeight,
                fontSize = 36.sp, letterSpacing = 1.sp
            ),
            color = p.ink
        )
        // 月份只是标签，没有切月能力——所以不画成胶囊按钮的样子，
        // 免得上手就点、点了没反应（切月要 LedgerBookViewModel 支持，尚未做）。
        Text(
            "${month.year} 年 ${month.monthValue} 月",
            style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.5.sp, fontFamily = Art.type.body),
            color = p.ink3,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "每一笔消费都是一笔分录：有借必有贷，借贷必相等。",
        style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body),
        color = p.ink2
    )
    Spacer(Modifier.height(36.dp))
}

/* ---------------- 科目余额表 ---------------- */

/** 双线（会计表头/合计线） */
private fun Modifier.doubleRule(bottom: Boolean, color: Color): Modifier = drawBehind {
    val thin = 1.dp.toPx()
    val gap = 2.5f.dp.toPx()
    val y1 = if (bottom) size.height - thin else 0f
    val y2 = if (bottom) size.height - thin - gap else gap
    drawLine(color, Offset(0f, y1), Offset(size.width, y1), thin)
    drawLine(color, Offset(0f, y2), Offset(size.width, y2), thin)
}

@Composable
private fun BalanceTable(ui: LedgerBookUiState) {
    val p = Art.colors
    val ruleColor = if (p.dark) p.accent else p.ink
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(560.dp)) {
            // 表头
            Row(
                Modifier
                    .fillMaxWidth()
                    .doubleRule(bottom = true, color = ruleColor)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TCell("科目", Modifier.weight(1.5f), TextAlign.Start, head = true)
                TCell("期初余额", Modifier.weight(1f), TextAlign.End, head = true)
                TCell("借方发生", Modifier.weight(1f), TextAlign.End, head = true)
                TCell("贷方发生", Modifier.weight(1f), TextAlign.End, head = true)
                TCell("期末余额", Modifier.weight(1f), TextAlign.End, head = true)
            }
            if (ui.assetRows.isNotEmpty()) {
                GroupRow("资产类")
                ui.assetRows.forEach { DataRow(it) }
                SubtotalRow("资产小计", ui.assetRows)
            }
            if (ui.liabilityRows.isNotEmpty()) {
                GroupRow("负债类")
                ui.liabilityRows.forEach { DataRow(it) }
                SubtotalRow("负债小计", ui.liabilityRows)
            }
            if (ui.expenseRows.isNotEmpty()) {
                GroupRow("支出类")
                ui.expenseRows.forEach { DataRow(it) }
                SubtotalRow("支出小计", ui.expenseRows)
            }
            if (ui.incomeRows.isNotEmpty()) {
                GroupRow("收入类")
                ui.incomeRows.forEach { DataRow(it) }
                SubtotalRow("收入小计", ui.incomeRows)
            }
            if (ui.equityRows.isNotEmpty()) {
                GroupRow("权益类")
                ui.equityRows.forEach { DataRow(it) }
                SubtotalRow("权益小计", ui.equityRows)
            }
            // 合计
            Row(
                Modifier
                    .fillMaxWidth()
                    .doubleRule(bottom = false, color = ruleColor)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TCell("合计", Modifier.weight(1.5f), TextAlign.Start, bold = true)
                TCell("—", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell(fmt(ui.totalDebitMinor), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell(fmt(ui.totalCreditMinor), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell("—", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
            }
            // 借贷不平是账做坏了，必须照实暴露，不许四舍五入糊过去
            if (ui.isUnbalanced) {
                val gap = ui.totalDebitMinor - ui.totalCreditMinor
                Text(
                    "借贷不平：借方 ${fmt(ui.totalDebitMinor)}，贷方 ${fmt(ui.totalCreditMinor)}，" +
                        "差额 ${fmt(gap)}（借方为正）",
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.body),
                    color = p.accent,
                )
            }
        }
    }
}

@Composable
private fun TCell(
    text: String, modifier: Modifier, align: TextAlign,
    head: Boolean = false, num: Boolean = false, bold: Boolean = false, faint: Boolean = false
) {
    val p = Art.colors
    Text(
        text,
        modifier = modifier.padding(horizontal = 6.dp),
        textAlign = align,
        style = TextStyle(
            fontFamily = when {
                head -> Art.type.display
                num -> Art.type.num
                else -> Art.type.body
            },
            fontWeight = when {
                head || bold -> FontWeight.SemiBold
                else -> FontWeight.Normal
            },
            fontSize = if (head) 12.sp else 13.sp,
            letterSpacing = if (head) 1.5.sp else 0.sp,
            fontFeatureSettings = if (num) "tnum" else null
        ),
        color = when {
            faint -> p.ink3
            head || bold -> p.ink
            num -> p.ink2
            else -> p.ink
        }
    )
}

@Composable
private fun GroupRow(name: String) {
    Text(
        name,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
        style = TextStyle(
            fontFamily = Art.type.display, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, letterSpacing = 4.sp
        ),
        color = Art.colors.accent
    )
}

@Composable
private fun DataRow(r: BalanceRowUi) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.5f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.code, style = TextStyle(fontSize = 10.5.sp, fontFamily = Art.type.num), color = p.ink3)
                Spacer(Modifier.width(10.dp))
                Text(r.name, style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.body), color = p.ink)
            }
            TCell(fmtOrDash(r.openingMinor), Modifier.weight(1f), TextAlign.End, num = true, faint = r.openingMinor == 0L)
            TCell(fmtOrDash(r.debitMinor), Modifier.weight(1f), TextAlign.End, num = true, faint = r.debitMinor == 0L)
            TCell(fmtOrDash(r.creditMinor), Modifier.weight(1f), TextAlign.End, num = true, faint = r.creditMinor == 0L)
            TCell(fmtOrDash(r.closingMinor), Modifier.weight(1f), TextAlign.End, num = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

@Composable
private fun SubtotalRow(name: String, rows: List<BalanceRowUi>) {
    val opening = rows.sumOf { it.openingMinor }
    val debit = rows.sumOf { it.debitMinor }
    val credit = rows.sumOf { it.creditMinor }
    val closing = rows.sumOf { it.closingMinor }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TCell(name, Modifier.weight(1.5f), TextAlign.Start, bold = true)
        TCell(fmtOrDash(opening), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(fmtOrDash(debit), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(fmtOrDash(credit), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(fmtOrDash(closing), Modifier.weight(1f), TextAlign.End, num = true, bold = true)
    }
}

/* ---------------- 凭证 ---------------- */

@Composable
private fun VoucherCard(v: VoucherUi) {
    val p = Art.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(p.radius))
            .background(p.card)
    ) {
        // 左侧强调线（极简主题省略）
        if (!p.stageAsCard) {
            Box(
                Modifier
                    .width(if (p.dark) 1.dp else 2.dp)
                    .height(150.dp)
                    .background(p.accent)
            )
        }
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.no,
                    style = TextStyle(fontFamily = Art.type.display, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, letterSpacing = 1.sp),
                    color = p.ink
                )
                Text(v.dateText, style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink3)
            }
            Spacer(Modifier.height(12.dp))
            v.lines.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                VLine(
                    text = (if (line.isDebit) "借：" else "贷：") + line.accountLabel,
                    amount = fmt(line.amountMinor),
                    indent = !line.isDebit,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 虚线分隔
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .drawBehind {
                        drawLine(
                            p.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))
                        )
                    }
            )
            Spacer(Modifier.height(10.dp))
            Text(v.memo, style = TextStyle(fontSize = 11.5.sp, letterSpacing = 0.5.sp, fontFamily = Art.type.body), color = p.ink3)
        }
    }
}

@Composable
private fun VLine(text: String, amount: String, indent: Boolean) {
    val p = Art.colors
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 30.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body), color = if (indent) p.ink2 else p.ink)
        Text(amount, style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = if (indent) p.ink2 else p.ink)
    }
}
