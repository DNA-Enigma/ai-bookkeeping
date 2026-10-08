package dev.dzsun.bookkeeping.feature.entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.money.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

/**
 * 待确认的解析卡片：金额 / 分类 / 商家 / 备注 / 日期 / 地点 / 明细，可就地编辑。
 *
 * 原与整屏录入页（AddEntryScreen）同文件；那条路由被「记一笔」半屏面板取代后，
 * 只剩这张卡还被 [AddEntrySheet] 用着，所以单独留下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsedCard(
    card: DraftCard,
    categories: List<AccountEntity>,
    currency: String,
    /** 自动入账阈值。拿它判「要不要提示核对」——界面不自建阈值。 */
    confidenceThreshold: Float,
    onAmountChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onPayeeChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDateChange: (Long) -> Unit,
    onPlaceChange: (String) -> Unit,
    onItemChange: (Int, ItemLine) -> Unit,
    onItemAdd: () -> Unit,
    onItemRemove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val displayDate = LocalDate.ofEpochDay(card.dateEpochDay)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = true,
                    onClick = { },
                    label = { Text(if (card.kind == EntryKind.INCOME) "收入" else "支出") },
                )
                Spacer(Modifier.weight(1f))
                if (!card.isHighConfidence(confidenceThreshold)) {
                    NeedsConfirmChip()
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }

            // 低置信（或压根拿不到置信度）才走到这张卡，明说一句让用户知道该核对什么。
            // 高置信的走的是自动入账，不会出现在这里。
            if (!card.isHighConfidence(confidenceThreshold)) {
                LowConfidenceHint(weakestField = card.weakestField)
                Spacer(Modifier.height(4.dp))
            }

            OutlinedTextField(
                value = card.amountText,
                onValueChange = onAmountChange,
                label = { Text("金额") },
                prefix = { Text("¥") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))

            CategoryPicker(
                selectedId = card.categoryId,
                categories = categories,
                onSelect = onCategoryChange,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = card.payee,
                onValueChange = onPayeeChange,
                label = { Text("商家 / 对象") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = card.note,
                onValueChange = onNoteChange,
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 2,
            )

            Spacer(Modifier.height(8.dp))

            PlaceField(value = card.place, onValueChange = onPlaceChange)

            Spacer(Modifier.height(12.dp))

            ItemsEditor(
                items = card.items,
                onItemChange = onItemChange,
                onItemAdd = onItemAdd,
                onItemRemove = onItemRemove,
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showDatePicker = true }) {
                    Text(displayDate.format(dateFormatter))
                }
                Spacer(Modifier.weight(1f))
                val amount = runCatching { Money.parse(card.amountText, currency) }.getOrNull()
                if (amount != null && !amount.isZero()) {
                    Text(
                        text = (if (card.kind == EntryKind.INCOME) "+" else "-") + amount.format(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (card.kind == EntryKind.INCOME) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = card.dateEpochDay * 86400000L)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val day = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                        onDateChange(day)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    selectedId: String,
    categories: List<AccountEntity>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.id == selectedId }?.name ?: "选择分类"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("分类") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    onClick = {
                        onSelect(cat.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
