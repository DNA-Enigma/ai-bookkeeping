package dev.dzsun.bookkeeping.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Slider
import dev.dzsun.bookkeeping.feature.entry.AutoConfirmSettings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.update.UpdateManifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onImportClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var darkMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从「安装未知应用」授权页回来后接着装；onInstallPermissionGranted 自己会查授权
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onInstallPermissionGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 分类管理
            item { SectionTitle("分类管理") }
            item {
                CategoryGroupCard(
                    icon = Icons.Default.Payments,
                    title = "支出分类",
                    subtitle = "${state.expenseCategories.size} 个",
                    items = state.expenseCategories,
                )
            }
            item {
                CategoryGroupCard(
                    icon = Icons.Default.Category,
                    title = "收入分类",
                    subtitle = "${state.incomeCategories.size} 个",
                    items = state.incomeCategories,
                )
            }

            // 账户
            item { SectionTitle("账户") }
            item {
                CategoryGroupCard(
                    icon = Icons.Default.AccountBalance,
                    title = "我的账户",
                    subtitle = "${state.accounts.size} 个 · 币种 ${state.currency}",
                    items = state.accounts,
                )
            }

            // 数据
            item { SectionTitle("数据") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onImportClick)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("导入流水", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "微信、支付宝、银行账单 · 先预览再入库",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 记账
            item { SectionTitle("记账") }
            item {
                AutoConfirmThresholdCard(
                    threshold = state.autoConfirmThreshold,
                    range = state.autoConfirmRange,
                    onChange = viewModel::onAutoConfirmThresholdChange,
                )
            }

            // 偏好
            item { SectionTitle("偏好") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { darkMode = !darkMode }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "跟随系统",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = darkMode, onCheckedChange = { darkMode = it })
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI 解析", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "自然语言 / 语音 / 票据识别",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 关于
            item { SectionTitle("关于") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI 记账", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "版本 ${state.versionName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    UpdateSection(
                        state = state.update,
                        onCheck = viewModel::checkForUpdate,
                        onDismiss = viewModel::dismissUpdate,
                        onDownload = { viewModel.downloadAndInstall(it) },
                        onOpenInstallPermission = {
                            // Android 8 起「安装未知应用」是每应用单独授权的
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
            }

            item { Spacer(Modifier.padding(24.dp)) }
        }
    }
}

/** 在线更新入口。手动检查的失败可以提示——用户正等着这个结果。 */
@Composable
private fun UpdateSection(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onOpenInstallPermission: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("检查更新", style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                is UpdateUiState.Idle -> TextButton(onClick = onCheck) { Text("检查") }
                is UpdateUiState.Checking -> CircularProgressIndicator(
                    modifier = Modifier.width(20.dp),
                    strokeWidth = 2.dp,
                )
                is UpdateUiState.UpToDate -> TextButton(onClick = onCheck) { Text("再查一次") }
                else -> Unit
            }
        }

        when (state) {
            is UpdateUiState.Available -> {
                Spacer(Modifier.height(8.dp))
                state.manifest.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDownload(state.manifest) }, modifier = Modifier.weight(1f)) {
                        Text("下载更新")
                    }
                    OutlinedButton(onClick = onDismiss) { Text("稍后") }
                }
            }

            is UpdateUiState.Downloading -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateUiState.ReadyToInstall -> {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onDownload(state.manifest) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开安装页")
                }
            }

            is UpdateUiState.NeedInstallPermission -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "系统要求先允许本应用安装未知来源应用，授权后回来即可继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenInstallPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("去授权")
                }
            }

            is UpdateUiState.Error -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) { Text("重试") }
                    TextButton(onClick = onDismiss) { Text("忽略") }
                }
            }

            else -> Unit
        }
    }
}

private fun statusLabel(state: UpdateUiState): String = when (state) {
    is UpdateUiState.Idle -> "有新版本时提醒我"
    is UpdateUiState.Checking -> "正在检查…"
    is UpdateUiState.UpToDate -> "已是最新版本"
    is UpdateUiState.Available -> "发现新版本 ${state.manifest.versionName}"
    is UpdateUiState.Downloading -> "正在下载…"
    is UpdateUiState.ReadyToInstall -> "安装包已就绪"
    is UpdateUiState.NeedInstallPermission -> "需要安装权限"
    is UpdateUiState.Error -> "检查失败"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun CategoryGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    items: List<AccountEntity>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { /* 后续接分类编辑页 */ }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (items.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items.forEach { account ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                account.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                account.currency,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自动入账阈值。**这是产品口径的旋钮，不是技术参数**，所以文案要说清两边的后果：
 * 调高＝问得更勤，调低＝记得更顺但更容易记错。
 */
@Composable
private fun AutoConfirmThresholdCard(
    threshold: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动入账置信度", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "识别置信度达到这个数的账**直接入账**，不再弹确认；低于它的仍然先让你核对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%.2f".format(threshold),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = threshold,
                onValueChange = onChange,
                valueRange = range,
                // 0.05 一档：再细也没有意义，用户分辨不出 0.83 和 0.84 的差别
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "更少打扰（容易记错）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "更多确认（更稳）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
