package dev.dzsun.bookkeeping.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.designsystem.CreamEnd
import dev.dzsun.bookkeeping.core.designsystem.CreamFill
import dev.dzsun.bookkeeping.core.designsystem.CreamStart
import dev.dzsun.bookkeeping.core.designsystem.CreamTrack
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import dev.dzsun.bookkeeping.core.designsystem.categoryColor
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayHeadingFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

/**
 * 记账首页：今日大数字 → 月度汇总与预算 → 分析条 → 按日分组的流水账本。
 * 结构对齐参考图，分析收在今日卡片下面。
 */
@Composable
fun LedgerScreen(
    onAddEntry: () -> Unit,
    onEntryClick: (String) -> Unit = {},
    onAskClick: () -> Unit = {},
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hideAmounts by viewModel.hideAmounts.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { HomeTopBar(onAskClick = onAskClick) }
            item {
                SummaryCard(
                    state = state,
                    hideAmounts = hideAmounts,
                    onToggleHide = viewModel::toggleHideAmounts,
                )
            }
            item { AnalysisStrip(state) }
            if (state.entries.isEmpty()) {
                item { EmptyLedger(isLoading = state.isLoading) }
            } else {
                val grouped = state.entries.groupBy { it.dateEpochDay }
                grouped.forEach { (epochDay, rows) ->
                    item(key = "heading-$epochDay") {
                        DayHeading(epochDay, rows)
                    }
                    items(rows, key = { it.journalId }) { row ->
                        EntryRow(
                            row = row,
                            hideAmounts = hideAmounts,
                            onClick = { onEntryClick(row.journalId) },
                        )
                    }
                }
            }
        }

        // 右侧悬浮 AI 球：贴右下角，压在流水上方，不挡分析条
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, IncomeGreen)))
                .clickable { onAskClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HomeTopBar(onAskClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "记账",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        // 日历：功能未实现，暂不展示
        IconButton(onClick = onAskClick) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "问账")
        }
    }
}

@Composable
private fun SummaryCard(
    state: LedgerUiState,
    hideAmounts: Boolean,
    onToggleHide: () -> Unit,
) {
    val currency = state.currency ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(CreamStart, CreamEnd)))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日支出(元)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (hideAmounts) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (hideAmounts) "显示金额" else "隐藏金额",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onToggleHide),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hideAmounts) "****" else Money.of(state.todayExpenseMinor, currency).format(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                fontSize = 40.sp,
            )

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                MonthCell(
                    label = "本月支出(元)",
                    value = state.monthExpenseMinor,
                    currency = currency,
                    hideAmounts = hideAmounts,
                    modifier = Modifier.weight(1f),
                )
                MonthCell(
                    label = "本月收入(元)",
                    value = state.monthIncomeMinor,
                    currency = currency,
                    hideAmounts = hideAmounts,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "月预算剩余 ${if (hideAmounts) "****" else Money.of(state.budgetRemainingMinor, currency).toPlainString()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑预算",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "已用 ${(state.budgetUsedFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CreamTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.budgetUsedFraction.coerceAtLeast(0.06f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CreamFill),
                )
            }
        }
    }
}

@Composable
private fun MonthCell(
    label: String,
    value: Long,
    currency: String,
    hideAmounts: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hideAmounts) "****" else Money.of(abs(value), currency).format(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 分析收在今日下面：本周 / 本月摘要条，点进报表由导航层处理。 */
@Composable
private fun AnalysisStrip(state: LedgerUiState) {
    val currency = state.currency ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AnalysisCell("本周支出", Money.of(state.monthExpenseMinor / 3, currency).format())
            AnalysisCell("日均支出", Money.of(state.monthExpenseMinor / 30, currency).format())
            AnalysisCell("本月结余", Money.of(abs(state.balanceMinor), currency).format())
        }
    }
}

@Composable
private fun AnalysisCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DayHeading(epochDay: Long, rows: List<LedgerRow>) {
    val day = LocalDate.ofEpochDay(epochDay)
    val expense = rows.filter { it.categoryType == AccountType.EXPENSE }.sumOf { it.amountMinor }
    val income = rows.filter { it.categoryType == AccountType.INCOME }.sumOf { it.amountMinor }
    val currency = rows.firstOrNull()?.currency ?: "CNY"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.format(dayHeadingFormatter),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "支${Money.of(expense, currency).toPlainString()}  收${Money.of(income, currency).toPlainString()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryRow(row: LedgerRow, hideAmounts: Boolean, onClick: () -> Unit = {}) {
    val isIncome = row.categoryType == AccountType.INCOME
    val amount = Money.of(abs(row.amountMinor), row.currency)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(categoryColor(row.categoryName).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.categoryName.take(1),
                color = categoryColor(row.categoryName),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.categoryName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val subtitle = listOfNotNull(
                row.payee?.takeIf { it.isNotBlank() },
                row.place?.takeIf { it.isNotBlank() },
                row.counterpartyName.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val amountText = when {
            hideAmounts -> "****"
            isIncome -> "+" + amount.toPlainString()
            else -> "-" + amount.toPlainString()
        }
        Text(
            text = amountText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isIncome) IncomeGreen else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyLedger(isLoading: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isLoading) "正在准备账本…" else "还没有账目，\n点下方 + 记第一笔。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
