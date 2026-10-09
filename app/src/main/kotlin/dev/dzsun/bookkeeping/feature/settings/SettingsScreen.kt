package dev.dzsun.bookkeeping.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dzsun.bookkeeping.core.designsystem.*
import dev.dzsun.bookkeeping.feature.home.HomeModule
import dev.dzsun.bookkeeping.feature.ledger.minorToYuanInput

/**
 * ============================================================
 *  设置页（集成版）
 *  新设计语言区块：界面风格 / 首页模块 / AI 助手 / 通知
 *  原有功能区块（保留并换肤）：账本与类目 / 自动入账阈值 / 版本更新
 *  数据源：SettingsViewModel（直接复用）
 * ============================================================
 */

@Composable
fun SettingsScreen(
    onImportClick: () -> Unit = {},
    onSignOut: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onDeviceViewModel: OnDeviceModelViewModel = hiltViewModel()
    val onDeviceState by onDeviceViewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Art.colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        SettingsHero()
        SectionHeader(1, "界面风格")
        ThemeGrid()
        Spacer(Modifier.height(40.dp))
        SectionHeader(2, "首页模块")
        ModuleToggles(state, viewModel)
        Spacer(Modifier.height(40.dp))
        SectionHeader(3, "AI 助手")
        AssistantBlock(state, viewModel)
        Spacer(Modifier.height(40.dp))
        SectionHeader(4, "账本")
        LedgerSection(state, onImportClick, viewModel::onBudgetYuanChange)
        Spacer(Modifier.height(40.dp))
        SectionHeader(5, "自动化")
        AutomationSection(state, viewModel)
        Spacer(Modifier.height(40.dp))
        SectionHeader(6, "端侧模型")
        OnDeviceModelSection(state = onDeviceState, viewModel = onDeviceViewModel)
        Spacer(Modifier.height(40.dp))
        SectionHeader(7, "关于")
        AboutSection(state, viewModel, onSignOut)
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun SettingsHero() {
    val p = Art.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(1.dp).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text("偏好 · 你的账房你做主", style = TextStyle(fontSize = 11.5.sp, letterSpacing = 3.sp, fontFamily = Art.type.body),
            color = if (p.dark) p.accent else p.ink2)
    }
    Spacer(Modifier.height(16.dp))
    Text("设置", style = TextStyle(fontFamily = Art.type.display, fontWeight = Art.type.heroWeight, fontSize = 38.sp, letterSpacing = 1.sp), color = p.ink)
    Spacer(Modifier.height(36.dp))
}

/* ---------------- 主题选择 2×2 ---------------- */

@Composable
private fun ThemeGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ArtStyle.entries.toList().chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowItems.forEach { s -> ThemeCard(s, Modifier.weight(1f)) }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeCard(style: ArtStyle, modifier: Modifier) {
    val p = Art.colors
    val selected = ArtThemeState.current == style
    val sp = style.palette()
    val st = style.type()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(p.radiusL))
            .background(p.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) p.accent else p.line, RoundedCornerShape(p.radiusL))
            .clickable { ArtThemeState.current = style },
    ) {
        Column(Modifier.fillMaxWidth().background(sp.bg).padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                style.previewGlyph,
                style = TextStyle(
                    fontFamily = st.display,
                    fontWeight = if (style == ArtStyle.MINIMAL) FontWeight.Light else FontWeight.Bold,
                    fontStyle = if (style == ArtStyle.EDITORIAL || style == ArtStyle.LUXE) FontStyle.Italic else FontStyle.Normal,
                    fontSize = 32.sp,
                ),
                color = if (style == ArtStyle.LUXE) sp.accent else sp.ink,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                sp.chart.take(3).forEach { c -> Box(Modifier.size(9.dp).clip(RoundedCornerShape(999.dp)).background(c)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(style.label, style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, fontFamily = Art.type.body), color = p.ink)
                Text(style.sub, style = TextStyle(fontSize = 11.sp, fontFamily = Art.type.body), color = p.ink3)
            }
            if (selected) {
                Box(Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, p.accent, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text("使用中", style = TextStyle(fontSize = 10.sp, letterSpacing = 1.sp), color = p.accent)
                }
            }
        }
    }
}

/* ---------------- 首页模块开关 ---------------- */

@Composable
private fun ModuleToggles(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column {
        ToggleRow("管家问候", "每日寒暄与今日摘要", state.modules.greeting) {
            viewModel.onModuleChange(HomeModule.GREETING, it)
        }
        ToggleRow("主动建议", "管家每日 3 则以内的洞察", state.modules.insights) {
            viewModel.onModuleChange(HomeModule.INSIGHTS, it)
        }
        ToggleRow("本月总览", "收支数字与预算执行", state.modules.overview) {
            viewModel.onModuleChange(HomeModule.OVERVIEW, it)
        }
        ToggleRow("近期流水", "最近 5 笔账目", state.modules.transactions) {
            viewModel.onModuleChange(HomeModule.TRANSACTIONS, it)
        }
        // 「财务体质」暂时不给开关：评分模型没接入，首页一律不渲染那个模块，
        // 让用户开关一个看不见的东西只会造成困惑。模型落地后与其它行一并恢复。
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text(desc, style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = p.ink3)
            }
            ArtSwitch(checked, onChange)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

/* ---------------- AI 助手 ---------------- */

@Composable
private fun AssistantBlock(state: SettingsUiState, viewModel: SettingsViewModel) {
    val p = Art.colors
    Text("人 设", style = TextStyle(fontSize = 12.sp, letterSpacing = 2.sp, fontFamily = Art.type.body), color = p.ink3)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentPersona.entries.forEach { persona ->
            ArtChip(
                persona.displayName,
                selected = state.persona == persona,
                onClick = { viewModel.onPersonaChange(persona) },
            )
        }
    }
    Spacer(Modifier.height(28.dp))
    Text("首 选 理 财 平 台", style = TextStyle(fontSize = 12.sp, letterSpacing = 2.sp, fontFamily = Art.type.body), color = p.ink3)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AGENT_PLATFORMS.forEach { name ->
            ArtChip(
                name,
                selected = state.platform == name,
                onClick = { viewModel.onPlatformChange(name) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("投资建议将以「复制金额 + 操作指引」的形式给出，由你手动在 ${state.platform} 完成。",
        style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp, fontFamily = Art.type.body), color = p.ink3)
}

/* ---------------- 账本（原功能：币种/类目/导入） ---------------- */

@Composable
private fun LedgerSection(
    state: SettingsUiState,
    onImportClick: () -> Unit,
    onBudgetYuanChange: (String) -> Unit,
) {
    val p = Art.colors
    InfoRow("本位币", state.currency)
    InfoRow("支出类目", "${state.expenseCategories.size} 个")
    InfoRow("账户", "${state.accounts.size} 个")
    BudgetRow(state, onBudgetYuanChange)
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("导入账单", style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text("微信 / 支付宝 CSV、XLSX 账单导入", style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = p.ink3)
            }
            Text("前往 →", style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp), color = p.accent,
                modifier = Modifier.clickable(onClick = onImportClick))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

/**
 * 月度预算。单位元、内部存分（[yuanToMinor] 整数换算，不碰浮点）。
 * 留空 = 不设预算，首页预算行会显示引导文案而不是 0/100%。
 */
@Composable
private fun BudgetRow(state: SettingsUiState, onBudgetYuanChange: (String) -> Unit) {
    val p = Art.colors
    var text by remember { mutableStateOf(minorToYuanInput(state.budgetMinor)) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("月度预算", style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text("设定每月开支上限，留空表示不设", style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = p.ink3)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        text = next
                        // 认不出的输入不写、不清——留着让用户改，不要静默改值
                        onBudgetYuanChange(next)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = Art.type.num, fontWeight = Art.type.numWeight,
                        fontSize = 15.sp, fontFeatureSettings = "tnum",
                        color = p.ink, textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.width(96.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            if (text.isEmpty()) {
                                Text("不设", style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.body), color = p.ink3)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text("元", style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = p.ink3)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
            Text(value, style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.ink2)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

/* ---------------- 自动化（原功能：自动入账阈值） ---------------- */

@Composable
private fun AutomationSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val p = Art.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("自动入账阈值", style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
            Text("${(state.autoConfirmThreshold * 100).toInt()}%",
                style = TextStyle(fontSize = 13.5.sp, fontFamily = Art.type.num, fontFeatureSettings = "tnum"), color = p.accent)
        }
        Text("AI 解析置信度高于此值时直接入账，不再询问",
            style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body), color = p.ink3)
        Slider(
            value = state.autoConfirmThreshold,
            onValueChange = viewModel::onAutoConfirmThresholdChange,
            valueRange = state.autoConfirmRange,
            colors = SliderDefaults.colors(
                thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line2,
            ),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
}

/* ---------------- 关于（原功能：版本/更新/退出） ---------------- */

@Composable
private fun AboutSection(state: SettingsUiState, viewModel: SettingsViewModel, onSignOut: () -> Unit) {
    val p = Art.colors
    InfoRow("版本", "${state.versionName} (${state.versionCode})")
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("检查更新", style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Art.type.body), color = p.ink)
                Spacer(Modifier.height(3.dp))
                Text(
                    when (val u = state.update) {
                        is UpdateUiState.Checking -> "正在检查……"
                        is UpdateUiState.UpToDate -> "已是最新版本"
                        is UpdateUiState.Available -> "发现新版本，可下载安装"
                        is UpdateUiState.Downloading -> "下载中……"
                        // u.reason 可能含主机名/URL，界面只给白名单文案。
                        is UpdateUiState.Error -> "网络不通，稍后再试"
                        else -> "手动检查新版本"
                    },
                    style = TextStyle(fontSize = 12.sp, fontFamily = Art.type.body),
                    color = if (state.update is UpdateUiState.Available) p.accent else p.ink3,
                )
            }
            when (val u = state.update) {
                is UpdateUiState.Available ->
                    Text("下载", style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp), color = p.accent,
                        modifier = Modifier.clickable { viewModel.downloadAndInstall(u.manifest) })
                else ->
                    Text("检查", style = TextStyle(fontSize = 12.5.sp, letterSpacing = 1.sp), color = p.accent,
                        modifier = Modifier.clickable(enabled = state.update !is UpdateUiState.Checking) { viewModel.checkForUpdate() })
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2))
    }
    // 退出登录
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, p.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onSignOut)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("退出登录", style = TextStyle(fontSize = 13.sp, letterSpacing = 3.sp, fontFamily = Art.type.body), color = p.warn)
    }
}
