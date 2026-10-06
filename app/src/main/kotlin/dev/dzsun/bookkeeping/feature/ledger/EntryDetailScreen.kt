package dev.dzsun.bookkeeping.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.JournalItemEntity
import dev.dzsun.bookkeeping.core.database.JournalSource
import dev.dzsun.bookkeeping.core.database.JournalStatus
import dev.dzsun.bookkeeping.core.database.LedgerRow
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import dev.dzsun.bookkeeping.core.money.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val detailDateFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA)

/**
 * 账目详情：金额与分类在上，锚点信息（日期/商家/地点/备注）在中，
 * 「买了什么」的明细在下。明细只描述，不参与记账——所以这里也不去核对
 * 明细之和是否等于总额。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    onBack: () -> Unit,
    viewModel: EntryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账目详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Unit
            state.row == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这笔账找不到了", style = MaterialTheme.typography.bodyLarge)
            }

            else -> EntryDetailBody(
                row = state.row!!,
                items = state.items,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun EntryDetailBody(
    row: LedgerRow,
    items: List<JournalItemEntity>,
    modifier: Modifier = Modifier,
) {
    val isIncome = row.categoryType == AccountType.INCOME
    val amount = Money.of(abs(row.amountMinor), row.currency)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = (if (isIncome) "+" else "-") + amount.format(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = if (isIncome) IncomeGreen else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                row.categoryName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("日期", LocalDate.ofEpochDay(row.dateEpochDay).format(detailDateFormatter))
                DetailRow("商家 / 对象", row.payee?.takeIf { it.isNotBlank() } ?: "—")
                if (!row.place.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(row.place, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                DetailRow("备注", row.note?.takeIf { it.isNotBlank() } ?: "—")
                DetailRow("来源", sourceLabel(row.source))
                DetailRow("状态", statusLabel(row.status))
                row.counterpartyName.takeIf { it.isNotBlank() }?.let {
                    DetailRow("对方账户", it)
                }
            }
        }

        ItemsSection(items = items, currency = row.currency)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 「买了什么」。这是明细存在的全部理由——金额、商户、分类都不足以让人
 * 几周后想起一笔支出，而小票上那行「拿铁 大杯」可以。
 */
@Composable
private fun ItemsSection(items: List<JournalItemEntity>, currency: String) {
    Column {
        Text(
            "买了什么",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                "没有明细。拍小票时会自动带上「买了什么」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        ItemRow(item, currency)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "明细只描述买了什么，记账金额以上方总额为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun ItemRow(item: JournalItemEntity, currency: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.description,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // 没填单价的行不硬凑金额——很多小票只给总额
        item.amountMinor?.let { minor ->
            Text(
                Money.of(abs(minor), currency).toPlainString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun sourceLabel(source: JournalSource): String = when (source) {
    JournalSource.MANUAL -> "手动记录"
    JournalSource.RECEIPT -> "拍票识别"
    JournalSource.VOICE -> "语音 / 文字"
    JournalSource.STATEMENT -> "流水导入"
    JournalSource.RECURRING -> "定期记账"
    JournalSource.ORDER -> "投资订单"
}

private fun statusLabel(status: JournalStatus): String = when (status) {
    JournalStatus.PENDING -> "待确认"
    JournalStatus.CLEARED -> "已核对"
    JournalStatus.VOID -> "已作废"
}
