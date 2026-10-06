package dev.dzsun.bookkeeping.feature.entry

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.designsystem.BrandBlue
import dev.dzsun.bookkeeping.core.designsystem.ExpenseOrange
import dev.dzsun.bookkeeping.core.designsystem.categoryColor

/**
 * 「记一笔」半屏面板：金额大字 + 分类宫格 + 数字键盘。
 * AI 拍照/相册识别结果复用同一面板的确认卡，不另起一条路由。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val photoCapture = rememberPhotoCapture(
        onPhotoCaptured = viewModel::onPhotoCaptured,
        onPhotoError = viewModel::onCaptureError,
    )
    val showAiResult =
        state.isParsing || state.cards.isNotEmpty() || state.pendingClarification != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 8.dp)) {
                    SegmentedButton(
                        selected = state.kind != EntryKind.INCOME,
                        onClick = { viewModel.onKindChange(EntryKind.EXPENSE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("支出") }
                    SegmentedButton(
                        selected = state.kind == EntryKind.INCOME,
                        onClick = { viewModel.onKindChange(EntryKind.INCOME) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("收入") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.onDateChange(java.time.LocalDate.now().toEpochDay()) }) {
                    Text("今天")
                }
                PhotoMenu(onPickGallery = photoCapture::pickFromGallery, onTakePhoto = photoCapture::takePhoto)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            state.parseError?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (showAiResult) {
                AiCaptureBody(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.heightIn(max = 440.dp),
                )
            } else {
                ManualSheetBody(state, viewModel)
            }
        }
    }

    if (state.saved) {
        androidx.compose.runtime.LaunchedEffect(state.saved) {
            onSaved()
        }
    }
}

/** 相册 / 相机两个入口。 */
@Composable
private fun PhotoMenu(onPickGallery: () -> Unit, onTakePhoto: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.CameraAlt, contentDescription = "拍照记账")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("从相册选择") },
                leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                onClick = {
                    expanded = false
                    onPickGallery()
                },
            )
            DropdownMenuItem(
                text = { Text("拍一张") },
                leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                onClick = {
                    expanded = false
                    onTakePhoto()
                },
            )
        }
    }
}

/**
 * 识别结果：进度 / 澄清 / 确认卡。
 * 契约要求失败也保留已抽出的字段，所以卡片与错误文案可以同时出现。
 */
@Composable
private fun AiCaptureBody(
    state: AddEntryUiState,
    viewModel: AddEntryViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isParsing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在识别票据…", style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.pendingClarification?.let { clarification ->
            ClarificationCard(
                clarification = clarification,
                onAnswer = { optionId, freeText ->
                    viewModel.onClarificationAnswer(optionId, freeText)
                },
            )
        }

        if (state.fieldEdits.isNotEmpty()) {
            FieldEditSummary(edits = state.fieldEdits)
        }

        state.cards.forEach { card ->
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
                onRemove = { viewModel.onCardRemove(card.id) },
            )
        }

        if (state.cards.isNotEmpty()) {
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
            TextButton(
                onClick = {
                    state.cards.forEach { viewModel.onCardRemove(it.id) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("放弃这些，改用手动录入")
            }
        }
    }
}

@Composable
private fun ManualSheetBody(state: AddEntryUiState, viewModel: AddEntryViewModel) {
    // 金额大字
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("¥", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = ExpenseOrange)
        Spacer(Modifier.width(8.dp))
        Text(
            text = state.amountText.ifBlank { "0" },
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
        )
    }

    // 分类宫格
    val categories = state.visibleCategories
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(horizontal = 12.dp),
    ) {
        items(categories, key = { it.id }) { cat ->
            CategoryCell(
                account = cat,
                selected = cat.id == state.categoryId,
                onClick = { viewModel.onCategoryChange(cat.id) },
            )
        }
    }

    // 备注
    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::onNoteChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("点击填写备注") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )

    state.amountError?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Numpad(
        onDigit = { d -> viewModel.onAmountChange(state.amountText + d) },
        onBackspace = { viewModel.onAmountChange(state.amountText.dropLast(1)) },
        onSave = {
            viewModel.saveManual()
        },
        saveEnabled = state.canSaveManual,
    )
}

@Composable
private fun CategoryCell(
    account: AccountEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) categoryColor(account.name).copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(categoryColor(account.name).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                account.name.take(1),
                fontWeight = FontWeight.Bold,
                color = categoryColor(account.name),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            account.name,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun Numpad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        val rows = listOf(
            listOf("7", "8", "9", "⌫"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("再记一笔", "0", ".", "保存"),
        )
        rows.forEach { keys ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keys.forEach { key ->
                    val isSave = key == "保存"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isSave && saveEnabled -> BrandBlue
                                    isSave -> BrandBlue.copy(alpha = 0.35f)
                                    key == "⌫" || key == "-" || key == "+" || key == "再记一笔" ->
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                                },
                            )
                            .clickable(enabled = !isSave || saveEnabled) {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "保存" -> onSave()
                                    "再记一笔" -> onSave()
                                    else -> if (key.length == 1) onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") {
                            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "删除")
                        } else {
                            Text(
                                key,
                                fontWeight = if (isSave) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSave) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                fontSize = if (key.length > 1) 13.sp else 18.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
