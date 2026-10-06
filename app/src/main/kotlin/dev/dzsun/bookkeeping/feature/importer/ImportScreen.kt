package dev.dzsun.bookkeeping.feature.importer

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.designsystem.ExpenseOrange
import dev.dzsun.bookkeeping.core.designsystem.IncomeGreen
import dev.dzsun.bookkeeping.core.money.Money
import dev.dzsun.bookkeeping.core.statement.ImportOutcome
import dev.dzsun.bookkeeping.core.statement.ImportPlan
import dev.dzsun.bookkeeping.core.statement.StatementDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 流水导入：选文件 → 输密码 → 预览 → 确认。
 *
 * **确认之前不落库。** 预览里把「将跳过」的重复项显式标出来——
 * 用户不该在按下确认之前以为全部入库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = onBack,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currency = state.currency

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入流水") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val stage = state.stage) {
                is ImportStage.SelectFile -> SelectFilePane(
                    state = state,
                    onPickFile = { name, bytes -> viewModel.onFilePicked(name, bytes) },
                    onFileError = viewModel::onFileReadFailed,
                    onAccountSelected = viewModel::onAccountSelected,
                    onStart = { viewModel.prepare(password = null) },
                )

                is ImportStage.NeedPassword -> PasswordPane(
                    wrongPassword = stage.wrongPassword,
                    isBusy = state.isBusy,
                    fileName = state.fileName,
                    onSubmit = viewModel::onPasswordSubmit,
                )

                is ImportStage.Preview -> PreviewPane(
                    plan = stage.plan,
                    state = state,
                    onConfirm = viewModel::confirmImport,
                    onBackToFile = viewModel::restart,
                )

                is ImportStage.Committed -> CommittedPane(
                    outcome = stage.outcome,
                    onDone = onDone,
                    onRestart = viewModel::restart,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 第一步：选文件

@Composable
private fun SelectFilePane(
    state: ImportUiState,
    onPickFile: (String, ByteArray) -> Unit,
    onFileError: (String) -> Unit,
    onAccountSelected: (String) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readStatementFile(context, uri) }
            loaded.onSuccess { (name, bytes) -> onPickFile(name, bytes) }
                .onFailure { onFileError(it.message ?: "打不开这个文件") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "选择微信、支付宝或银行导出的账单文件。支持 CSV 与加密 ZIP。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 文件
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        pickFile.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "text/*",
                            ),
                        )
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("账单文件", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.fileName ?: "点这里选择文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 账户：流水里没有这个信息，必须用户指定
        AccountPicker(
            accounts = state.accounts,
            selectedId = state.selectedAccountId,
            onSelected = onAccountSelected,
        )

        state.error?.let { msg ->
            ErrorBanner(msg)
        }

        state.warnings.forEach { w ->
            Text(
                "⚠ $w",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onStart,
            enabled = state.canPrepare,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("解析账单")
            }
        }
    }
}

@Composable
private fun AccountPicker(
    accounts: List<AccountEntity>,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = accounts.find { it.id == selectedId }?.name ?: "选择账户"

    Card(modifier = Modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (accounts.isNotEmpty()) expanded = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("入账到哪个账户", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (accounts.isEmpty()) "还没有账户，请先在设置里添加"
                        else selectedName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            onSelected(account.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 第二步：密码

@Composable
private fun PasswordPane(
    wrongPassword: Boolean,
    isBusy: Boolean,
    fileName: String?,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "这份账单是加密的",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "银行/支付平台导出的 ZIP 通常带密码。$fileName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("解压密码") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                TextButtonSmall(
                    label = if (showPassword) "隐藏" else "显示",
                    onClick = { showPassword = !showPassword },
                )
            },
            isError = wrongPassword,
            supportingText = if (wrongPassword) {
                { Text(WRONG_PASSWORD_MESSAGE, color = MaterialTheme.colorScheme.error) }
            } else {
                { Text("一般是身份证后六位或平台要求的密码") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onSubmit(password) },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("用这个密码解析")
            }
        }
    }
}

@Composable
private fun TextButtonSmall(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- 第三步：预览

@Composable
private fun PreviewPane(
    plan: ImportPlan,
    state: ImportUiState,
    onConfirm: () -> Unit,
    onBackToFile: () -> Unit,
) {
    val currency = state.currency
    val summary = remember(plan, currency) { plan.toSummary(currency) }
    val rows = remember(plan) { plan.toPreviewRows() }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryCard(summary, plan.formatName) }
            if (state.warnings.isNotEmpty()) {
                item {
                    Column {
                        state.warnings.forEach { w ->
                            Text(
                                "⚠ $w",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "逐条明细（${rows.size} 行）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(rows, key = { "${it.kind}-${it.rowNumber}" }) { row ->
                PreviewRowCard(row, currency)
            }
            item { Spacer(Modifier.height(72.dp)) }
        }

        // 底部确认条：钉住，避免长列表滑到一半找不到按钮
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBackToFile,
                modifier = Modifier.height(48.dp),
            ) {
                Text("换一份")
            }
            Button(
                onClick = onConfirm,
                enabled = summary.importable && !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("确认导入 ${summary.importCount} 笔")
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: ImportSummary, formatName: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (formatName != null) {
                Text(
                    formatName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "文件共 ${summary.totalRows} 行",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            SummaryLine(
                label = "将导入",
                value = "${summary.importCount} 笔",
                detail = "合计 ${Money.of(summary.importAmountMinor, summary.currency).format()}" +
                    if (summary.importCount > 0) {
                        "（支 ${Money.of(summary.importExpenseMinor, summary.currency).toPlainString()}" +
                            " · 收 ${Money.of(summary.importIncomeMinor, summary.currency).toPlainString()}）"
                    } else {
                        ""
                    },
                emphasis = true,
            )
            SummaryLine(
                label = "将跳过 · 疑似重复",
                value = "${summary.skipDuplicateCount} 笔",
                detail = if (summary.skipDuplicateCount > 0) {
                    "合计 ${Money.of(summary.skipDuplicateAmountMinor, summary.currency).format()} · 不会入库"
                } else {
                    null
                },
            )
            if (summary.alreadyImportedCount > 0) {
                SummaryLine(
                    label = "已导入过",
                    value = "${summary.alreadyImportedCount} 笔",
                    detail = "状态没变，直接跳过",
                )
            }
            if (summary.statusChangedCount > 0) {
                SummaryLine(
                    label = "状态变化",
                    value = "${summary.statusChangedCount} 笔",
                    detail = "多半是退款，需单独处理，本次不导入",
                )
            }
            if (summary.excludedCount > 0) {
                SummaryLine(
                    label = "不入库",
                    value = "${summary.excludedCount} 笔",
                    detail = "转账/红包等资金转移，不是消费",
                )
            }
            if (summary.unusableCount > 0) {
                SummaryLine(
                    label = "无法解析",
                    value = "${summary.unusableCount} 笔",
                    detail = "缺单号/金额/时间",
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String,
    detail: String?,
    emphasis: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasis) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Medium,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun PreviewRowCard(row: PreviewRow, currency: String) {
    val skipped = row.kind != PreviewRow.Kind.IMPORT
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (skipped) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 方向圆点：支出橙、收入绿
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (row.direction == StatementDirection.INCOME) IncomeGreen else ExpenseOrange,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.merchant?.takeIf { it.isNotBlank() }
                            ?: row.description?.takeIf { it.isNotBlank() }
                            ?: "第 ${row.rowNumber} 行",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    val badge = skipLabel(row)
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        SkipBadge(badge)
                    }
                }
                val subtitle = buildString {
                    append(formatPreviewDate(row.dateEpochDay))
                    row.categoryName?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                        if (row.categoryFromHistory) append("（按历史）")
                    }
                    if (row.duplicateCount > 0) {
                        append(" · 疑似重复 ${row.duplicateCount} 笔")
                    }
                    row.skipReason?.takeIf { row.kind != PreviewRow.Kind.DUPLICATE }?.let {
                        append(" · ")
                        append(it)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (row.amountMinor == 0L && row.kind == PreviewRow.Kind.UNUSABLE) {
                    "—"
                } else {
                    formatSignedAmount(row.amountMinor, row.direction, currency)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (row.direction == StatementDirection.INCOME) IncomeGreen else ExpenseOrange,
            )
        }
    }
}

@Composable
private fun SkipBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- 第四步：结果

@Composable
private fun CommittedPane(
    outcome: ImportOutcome,
    onDone: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = IncomeGreen,
        )
        Text(
            "已导入 ${outcome.imported} 笔",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (outcome.failures.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${outcome.failures.size} 笔写入失败",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    outcome.failures.forEach { msg ->
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Text(
                "已按预览结果落账。明细可在账本里逐笔查看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("完成")
        }
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("再导入一份")
        }
    }
}

// ---------------------------------------------------------------- 杂项

@Composable
private fun ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class LoadedStatement(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is LoadedStatement && other.name == name && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = name.hashCode() * 31 + bytes.contentHashCode()
}

private fun readStatementFile(
    context: android.content.Context,
    uri: Uri,
): Result<LoadedStatement> = runCatching {
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    } ?: uri.lastPathSegment ?: "账单文件"

    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("打不开这个文件")
    LoadedStatement(name, bytes)
}
