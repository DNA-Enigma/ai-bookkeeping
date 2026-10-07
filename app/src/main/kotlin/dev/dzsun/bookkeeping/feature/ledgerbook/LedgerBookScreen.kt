package dev.dzsun.bookkeeping.feature.ledgerbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.dzsun.bookkeeping.core.designsystem.*

/**
 * ============================================================
 *  账簿页 · 复式记账（与 HTML 原型 #pg-ledger 1:1 对应）
 *  科目余额表（双线表头/合计）+ 近期凭证（借/贷分录）
 * ============================================================
 */

private data class BalRow(
    val code: String, val name: String,
    val open: String, val debit: String, val credit: String, val close: String
)

private val assetRows = listOf(
    BalRow("1001", "库存现金", "1,200.00", "—", "500.00", "700.00"),
    BalRow("1002", "银行存款", "28,400.00", "24,300.00", "10,952.94", "41,747.06"),
    BalRow("1101", "理财 · 余额宝", "15,000.00", "8,000.00", "—", "23,000.00")
)
private val expenseRows = listOf(
    BalRow("5101", "餐饮支出", "—", "3,665.00", "—", "3,665.00"),
    BalRow("5102", "居住支出", "—", "2,749.00", "—", "2,749.00"),
    BalRow("5103", "其他支出", "—", "5,038.94", "—", "5,038.94")
)
private val incomeRows = listOf(
    BalRow("6001", "工资收入", "—", "—", "24,300.00", "24,300.00")
)

private data class Voucher(
    val no: String, val date: String,
    val debit: String, val debitAmt: String,
    val credit: String, val creditAmt: String,
    val memo: String
)

private val vouchers = listOf(
    Voucher("记 · 第 0012 号", "10 月 07 日", "餐饮支出 — 午餐", "86.00", "银行存款", "86.00", "摘要：桂满陇午餐 · 附单据 1 张"),
    Voucher("记 · 第 0011 号", "10 月 07 日", "交通支出 — 地铁", "6.00", "库存现金", "6.00", "摘要：早高峰通勤"),
    Voucher("记 · 第 0010 号", "10 月 01 日", "银行存款", "24,300.00", "工资收入 — 九月", "24,300.00", "摘要：月度工资到账 · 已自动分类")
)

@Composable
fun LedgerBookScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Art.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        LedgerHero()
        SectionHeader(1, "余额试算")
        BalanceTable()
        Spacer(Modifier.height(44.dp))
        SectionHeader(2, "近期凭证") { /* TODO 全部凭证 */ }
        vouchers.forEach { VoucherCard(it); Spacer(Modifier.height(14.dp)) }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun LedgerHero() {
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
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .border(1.dp, p.line, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text("2025 年 10 月", style = TextStyle(fontSize = 11.5.sp, letterSpacing = 1.5.sp), color = p.ink2)
        }
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
private fun BalanceTable() {
    val p = Art.colors
    val ruleColor = if (p.dark) p.accent else p.ink
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.widthIn(min = 560.dp)) {
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
            GroupRow("资产类")
            assetRows.forEach { DataRow(it) }
            SubtotalRow("资产小计", "44,600.00", "32,300.00", "11,452.94", "65,447.06")
            GroupRow("支出类")
            expenseRows.forEach { DataRow(it) }
            GroupRow("收入类")
            incomeRows.forEach { DataRow(it) }
            // 合计
            Row(
                Modifier
                    .fillMaxWidth()
                    .doubleRule(bottom = false, color = ruleColor)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TCell("合计", Modifier.weight(1.5f), TextAlign.Start, bold = true)
                TCell("44,600.00", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell("43,752.94", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell("35,752.94", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
                TCell("—", Modifier.weight(1f), TextAlign.End, num = true, bold = true)
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
private fun DataRow(r: BalRow) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.5f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.code, style = TextStyle(fontSize = 10.5.sp, fontFamily = Art.type.num), color = p.ink3)
                Spacer(Modifier.width(10.dp))
                Text(r.name, style = TextStyle(fontSize = 13.sp, fontFamily = Art.type.body), color = p.ink)
            }
            TCell(r.open, Modifier.weight(1f), TextAlign.End, num = true, faint = r.open == "—")
            TCell(r.debit, Modifier.weight(1f), TextAlign.End, num = true, faint = r.debit == "—")
            TCell(r.credit, Modifier.weight(1f), TextAlign.End, num = true, faint = r.credit == "—")
            TCell(r.close, Modifier.weight(1f), TextAlign.End, num = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

@Composable
private fun SubtotalRow(name: String, open: String, debit: String, credit: String, close: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TCell(name, Modifier.weight(1.5f), TextAlign.Start, bold = true)
        TCell(open, Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(debit, Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(credit, Modifier.weight(1f), TextAlign.End, num = true, bold = true)
        TCell(close, Modifier.weight(1f), TextAlign.End, num = true, bold = true)
    }
}

/* ---------------- 凭证 ---------------- */

@Composable
private fun VoucherCard(v: Voucher) {
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
                Text(v.date, style = TextStyle(fontSize = 11.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink3)
            }
            Spacer(Modifier.height(12.dp))
            VLine("借：${v.debit}", v.debitAmt, indent = false)
            Spacer(Modifier.height(4.dp))
            VLine("贷：${v.credit}", v.creditAmt, indent = true)
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
