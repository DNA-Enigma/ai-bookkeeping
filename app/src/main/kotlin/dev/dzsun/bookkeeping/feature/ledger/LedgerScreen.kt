package dev.dzsun.bookkeeping.feature.ledger

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayHeadingFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    onAddEntry: () -> Unit,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(state.monthLabel.ifEmpty { "账目" }) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddEntry) {
                Icon(Icons.Default.Add, contentDescription = "记一笔")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 币种未知时不渲染合计：Money 不接受空币种，这是刻意的
            state.currency?.let { currency ->
                SummaryCard(state, currency)
                HorizontalDivider()
            }
            if (state.entries.isEmpty()) {
                EmptyLedger(isLoading = state.isLoading)
            } else {
                EntryList(state.entries)
            }
        }
    }
}

@Composable
private fun SummaryCard(state: LedgerUiState, currency: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryCell("支出", Money.of(state.monthExpenseMinor, currency), negative = true)
            SummaryCell("收入", Money.of(state.monthIncomeMinor, currency), negative = false)
            SummaryCell(
                "结余",
                Money.of(abs(state.balanceMinor), currency),
                negative = state.balanceMinor < 0,
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, amount: Money, negative: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (negative) "-${amount.format()}" else amount.format(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyLedger(isLoading: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (isLoading) "正在准备账本…" else "还没有账目，\n点右下角记第一笔。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EntryList(entries: List<LedgerRow>) {
    // 按日分组，同一天的头只出一次
    val grouped = entries.groupBy { it.dateEpochDay }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        grouped.forEach { (epochDay, rows) ->
            item(key = "heading-$epochDay") {
                DayHeading(epochDay)
            }
            items(rows, key = { it.journalId }) { row ->
                EntryRow(row)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun DayHeading(epochDay: Long) {
    Text(
        text = LocalDate.ofEpochDay(epochDay).format(dayHeadingFormatter),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(row: LedgerRow) {
    // 分类一侧的分录恒与"钱的流向"相反：支出分录为正、收入分录为负，
    // 所以两者都要取负才是用户看到的方向。只翻收入会让支出显示成 +¥38.50。
    val signedMinor = -row.amountMinor
    val amount = Money.of(abs(signedMinor), row.currency)
    val subtitle = listOfNotNull(row.payee?.takeIf { it.isNotBlank() }, row.counterpartyName.takeIf { it.isNotBlank() })
        .joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.categoryName, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (signedMinor > 0) "+${amount.format()}" else "-${amount.format()}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (signedMinor > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
