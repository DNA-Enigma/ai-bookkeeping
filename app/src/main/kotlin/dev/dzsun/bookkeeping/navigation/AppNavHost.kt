package dev.dzsun.bookkeeping.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import dev.dzsun.bookkeeping.core.designsystem.Art
import dev.dzsun.bookkeeping.core.designsystem.ArtTheme
import dev.dzsun.bookkeeping.core.designsystem.ArtThemeState
import dev.dzsun.bookkeeping.feature.ask.AskScreen
import dev.dzsun.bookkeeping.feature.chat.ChatScreen
import dev.dzsun.bookkeeping.feature.entry.AddEntrySheet
import dev.dzsun.bookkeeping.feature.home.HomeScreen
import dev.dzsun.bookkeeping.feature.importer.ImportScreen
import dev.dzsun.bookkeeping.feature.ledger.EntryDetailScreen
import dev.dzsun.bookkeeping.feature.ledger.LedgerScreen
import dev.dzsun.bookkeeping.feature.ledgerbook.LedgerBookScreen
import dev.dzsun.bookkeeping.feature.report.ReportScreen
import dev.dzsun.bookkeeping.feature.settings.SettingsScreen
import dev.dzsun.bookkeeping.feature.stats.StatsScreen

object Routes {
    const val HOME = "home"
    const val REPORT = "report"
    const val LEDGER_BOOK = "ledgerbook"
    const val SETTINGS = "settings"
    const val CHAT = "chat"
    /** 问账页。不是 Tab，是压栈子页面——首页 AI 球与报表页都指向它。 */
    const val ASK = "ask"
    /** 完整记账流水（首页「近期流水 · 全部 →」）。 */
    const val LEDGER = "ledger"
    /** 详细统计（报表页「详细统计 →」，含 AI 月度小结）。 */
    const val STATS = "stats"
    const val ENTRY_DETAIL = "entry/{journalId}"
    const val IMPORT = "import"

    fun entryDetail(journalId: String) = "entry/$journalId"
}

private data class TabSpec(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabSpec(Routes.HOME, "首页", Icons.Outlined.Home),
    TabSpec(Routes.REPORT, "报表", Icons.Outlined.ShowChart),
    TabSpec(Routes.LEDGER_BOOK, "账簿", Icons.Outlined.MenuBook),
    TabSpec(Routes.SETTINGS, "设置", Icons.Outlined.Settings),
)

/**
 * 新导航骨架（替换原 AppNavHost）：
 *  底部四 Tab：首页 · 报表 · 〔＋〕· 账簿 · 设置
 *  中央 ＋ 打开原有的 AddEntrySheet（AI 解析/拍照/澄清都在，不重复造）；
 *  问账 / 完整流水 / 详细统计 / AI 对话 / 凭证详情 / 账单导入为压栈子页面，不显示底栏。
 *
 * 这四条压栈路由是 152baa7 重构时漏掉的：它们原先挂在旧 Tab 上，Tab 换掉后
 * 没有任何入口指向它们，于是整页不可达。现在各自从新界面上的真实入口进入——
 * 问账 ← 首页 AI 球 / 报表「查看 AI 账单分析」；完整流水 ← 首页「近期流水 · 全部」；
 * 详细统计 ← 报表「详细统计」。
 *
 * MainActivity 无需改动：RootFlow 仍调 AppNavHost(onSignOut)。
 */
@Composable
fun AppNavHost(onSignOut: () -> Unit = {}) {
    ArtTheme(ArtThemeState.current) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        var showAddSheet by remember { mutableStateOf(false) }
        val isDetail = currentRoute?.startsWith("entry/") == true ||
            currentRoute == Routes.IMPORT || currentRoute == Routes.CHAT ||
            currentRoute == Routes.ASK || currentRoute == Routes.LEDGER ||
            currentRoute == Routes.STATS

        Scaffold(
            containerColor = Art.colors.bg,
            bottomBar = {
                if (!isDetail) {
                    ArtBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { tab ->
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onAdd = { showAddSheet = true },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenChat = { navController.navigate(Routes.CHAT) },
                        onEntryClick = { id -> navController.navigate(Routes.entryDetail(id)) },
                        onAddEntry = { showAddSheet = true },
                        onImportClick = { navController.navigate(Routes.IMPORT) },
                        onAskClick = { navController.navigate(Routes.ASK) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenLedger = { navController.navigate(Routes.LEDGER) },
                    )
                }
                composable(Routes.REPORT) {
                    ReportScreen(
                        onAskClick = { navController.navigate(Routes.ASK) },
                        onOpenDetailStats = { navController.navigate(Routes.STATS) },
                    )
                }
                composable(Routes.LEDGER_BOOK) { LedgerBookScreen() }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onImportClick = { navController.navigate(Routes.IMPORT) },
                        onSignOut = onSignOut,
                    )
                }
                composable(Routes.CHAT) {
                    ChatScreen(onClose = { navController.popBackStack() })
                }
                composable(Routes.ASK) {
                    AskScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.LEDGER) {
                    LedgerScreen(
                        onAddEntry = { showAddSheet = true },
                        onEntryClick = { id -> navController.navigate(Routes.entryDetail(id)) },
                        onAskClick = { navController.navigate(Routes.ASK) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.STATS) {
                    StatsScreen(
                        onAskClick = { navController.navigate(Routes.ASK) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.IMPORT) {
                    ImportScreen(
                        onBack = { navController.popBackStack() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.ENTRY_DETAIL,
                    arguments = listOf(navArgument("journalId") { type = NavType.StringType }),
                ) {
                    EntryDetailScreen(onBack = { navController.popBackStack() })
                }
            }
        }

        if (showAddSheet) {
            AddEntrySheet(
                onDismiss = { showAddSheet = false },
                onSaved = { showAddSheet = false },
            )
        }
    }
}

/* ---------------- 艺术主题底栏（中央凸起 FAB） ---------------- */

@Composable
private fun ArtBottomBar(
    currentRoute: String?,
    onSelect: (TabSpec) -> Unit,
    onAdd: () -> Unit,
) {
    val p = Art.colors
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.bg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.take(2).forEach { tab ->
                NavItem(tab, currentRoute == tab.route, Modifier.weight(1f)) { onSelect(tab) }
            }
            Spacer(Modifier.weight(1f))   // FAB 空位
            tabs.drop(2).forEach { tab ->
                NavItem(tab, currentRoute == tab.route, Modifier.weight(1f)) { onSelect(tab) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line2).align(Alignment.TopCenter))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(if (p.dark) p.accent else p.ink)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add, contentDescription = "记一笔",
                tint = if (p.dark) Color(0xFF131109) else p.bg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun NavItem(tab: TabSpec, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = Art.colors
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            tab.icon, contentDescription = tab.label,
            tint = if (selected) p.accent else p.ink3,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            style = TextStyle(fontSize = 10.sp, letterSpacing = 1.5.sp, fontFamily = Art.type.body),
            color = if (selected) p.accent else p.ink3,
        )
    }
}
