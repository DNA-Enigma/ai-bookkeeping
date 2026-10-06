package dev.dzsun.bookkeeping.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.money.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    onDone: () -> Unit,
    viewModel: AddEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved, state.allSaved) {
        if (state.saved || state.allSaved) onDone()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("记一笔") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 模式切换：AI 说一句 vs 手动填表
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = state.mode == EntryMode.AI,
                    onClick = { viewModel.onModeChange(EntryMode.AI) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("AI 记账")
                }
                SegmentedButton(
                    selected = state.mode == EntryMode.MANUAL,
                    onClick = { viewModel.onModeChange(EntryMode.MANUAL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("手动录入")
                }
            }

            when (state.mode) {
                EntryMode.AI -> AiEntryBody(state, viewModel)
                EntryMode.MANUAL -> ManualEntryBody(state, viewModel)
            }
        }
    }
}

// ========================= AI 模式 =========================

@Composable
private fun AiEntryBody(state: AddEntryUiState, viewModel: AddEntryViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 自然语言输入区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "说一句就记完",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "例如：今天打车花了 30，午饭 18，昨天在星巴克咖啡 32",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.rawText,
                    onValueChange = viewModel::onRawTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入或说出你的账单…") },
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = viewModel::onVoiceClick) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.isListening) "聆听中…" else "语音")
                    }
                    val photoCapture = rememberPhotoCapture(
                        onPhotoCaptured = viewModel::onPhotoCaptured,
                        onPhotoError = viewModel::onCaptureError,
                    )
                    OutlinedButton(onClick = photoCapture::pickFromGallery) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("相册")
                    }
                    OutlinedButton(onClick = photoCapture::takePhoto) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("拍照")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = viewModel::parseNow, enabled = !state.isParsing) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("解析")
                    }
                }
            }
        }

        if (state.isParsing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        state.parseError?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 澄清渲染器：服务端/本地合成的问题优先于卡片表单
        state.pendingClarification?.let { clarification ->
            ClarificationCard(
                clarification = clarification,
                onAnswer = { optionId, freeText ->
                    viewModel.onClarificationAnswer(optionId, freeText)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.fieldEdits.isNotEmpty()) {
            FieldEditSummary(
                edits = state.fieldEdits,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.cards.isEmpty() && !state.isParsing) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "上面说一句，AI 帮你拆成账单卡片",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.cards, key = { it.id }) { card ->
                    ParsedCard(
                        card = card,
                        categories = if (card.kind == EntryKind.INCOME) state.incomeCategories
                        else state.expenseCategories,
                        currency = state.currency.ifBlank { "CNY" },
                        onAmountChange = { viewModel.onCardAmountChange(card.id, it) },
                        onCategoryChange = { viewModel.onCardCategoryChange(card.id, it) },
                        onPayeeChange = { viewModel.onCardPayeeChange(card.id, it) },
                        onNoteChange = { viewModel.onCardNoteChange(card.id, it) },
                        onDateChange = { viewModel.onCardDateChange(card.id, it) },
                        onPlaceChange = { viewModel.onCardPlaceChange(card.id, it) },
                        onItemChange = { index, line -> viewModel.onCardItemChange(card.id, index, line) },
                        onItemAdd = { viewModel.onCardItemAdd(card.id) },
                        onItemRemove = { viewModel.onCardItemRemove(card.id, it) },
                        onRemove = { viewModel.onCardRemove(card.id) },
                    )
                }
                item {
                    Button(
                        onClick = viewModel::confirmCards,
                        enabled = state.canConfirmCards,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.cards.size > 1) "全部入账（${state.cards.size} 笔）" else "确认入账",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParsedCard(
    card: DraftCard,
    categories: List<AccountEntity>,
    currency: String,
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
                if (!card.isHighConfidence) {
                    NeedsConfirmChip()
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
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

// ========================= 手动模式 =========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEntryBody(state: AddEntryUiState, viewModel: AddEntryViewModel) {
    var showDatePicker by remember { mutableStateOf(false) }
    val displayDate = LocalDate.ofEpochDay(state.dateEpochDay)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntryKind.entries.forEach { kind ->
                FilterChip(
                    selected = state.kind == kind,
                    onClick = { viewModel.onKindChange(kind) },
                    label = {
                        Text(
                            when (kind) {
                                EntryKind.EXPENSE -> "支出"
                                EntryKind.INCOME -> "收入"
                                EntryKind.TRANSFER -> "转账"
                            },
                        )
                    },
                )
            }
        }

        OutlinedTextField(
            value = state.amountText,
            onValueChange = viewModel::onAmountChange,
            label = { Text("金额") },
            prefix = { Text("¥") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.amountError != null,
            supportingText = state.amountError?.let { { Text(it) } },
        )

        if (state.kind == EntryKind.TRANSFER) {
            AccountPicker("从", state.fromAccountId, state.accounts, viewModel::onFromAccountChange)
            AccountPicker("到", state.toAccountId, state.accounts, viewModel::onToAccountChange)
        } else {
            CategoryPicker(
                selectedId = state.categoryId,
                categories = state.visibleCategories,
                onSelect = viewModel::onCategoryChange,
            )
            AccountPicker("账户", state.fromAccountId, state.accounts, viewModel::onFromAccountChange)
            OutlinedTextField(
                value = state.payee,
                onValueChange = viewModel::onPayeeChange,
                label = { Text("商家 / 对象") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
        )

        PlaceField(value = state.place, onValueChange = viewModel::onPlaceChange)

        ItemsEditor(
            items = state.items,
            onItemChange = viewModel::onItemChange,
            onItemAdd = viewModel::onItemAdd,
            onItemRemove = viewModel::onItemRemove,
        )

        TextButton(onClick = { showDatePicker = true }) {
            Text("日期：${displayDate.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA))}")
        }

        Button(
            onClick = viewModel::saveManual,
            enabled = state.canSaveManual,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("保存")
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.dateEpochDay * 86400000L)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val day = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                        viewModel.onDateChange(day)
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
private fun AccountPicker(
    label: String,
    selectedId: String,
    accounts: List<AccountEntity>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = accounts.firstOrNull { it.id == selectedId }?.name ?: "选择"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(acc.name) },
                    onClick = {
                        onSelect(acc.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
