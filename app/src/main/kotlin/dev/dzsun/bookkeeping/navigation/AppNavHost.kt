package dev.dzsun.bookkeeping.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.dzsun.bookkeeping.feature.discover.DiscoverScreen
import dev.dzsun.bookkeeping.feature.entry.AddEntrySheet
import dev.dzsun.bookkeeping.feature.ledger.EntryDetailScreen
import dev.dzsun.bookkeeping.feature.ledger.LedgerScreen
import dev.dzsun.bookkeeping.feature.settings.SettingsScreen
import dev.dzsun.bookkeeping.feature.stats.StatsScreen

object Routes {
    const val LEDGER = "ledger"
    const val STATS = "stats"
    const val DISCOVER = "discover"
    const val SETTINGS = "settings"
    const val ENTRY_DETAIL = "entry/{journalId}"
    const val IMPORT = "import"
    const val ASK = "ask"

    fun entryDetail(journalId: String) = "entry/$journalId"
}

private data class TabSpec(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val tabs = listOf(
    TabSpec(Routes.LEDGER, "记账", Icons.Filled.Wallet, Icons.Outlined.Wallet),
    TabSpec(Routes.STATS, "报表", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    TabSpec(Routes.DISCOVER, "发现", Icons.Filled.CompassCalibration, Icons.Outlined.CompassCalibration),
    TabSpec(Routes.SETTINGS, "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * 五宫格底栏：记账 · 报表 · 〔+〕· 发现 · 设置。
 * 中央 + 是「记一笔」半屏面板，不占路由。
 * 详情页（entry/{id}）不显示底栏——它是一条压栈的子页面。
 */
@Composable
fun AppNavHost(onSignOut: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var showAddSheet by remember { mutableStateOf(false) }
    val isDetail = currentRoute?.startsWith("entry/") == true ||
        currentRoute == Routes.IMPORT ||
        currentRoute == Routes.ASK

    Scaffold(
        bottomBar = {
            if (!isDetail) {
                NavigationBar {
                    // 左侧两个
                    tabs.take(2).forEach { tab ->
                        TabItem(tab, currentRoute, navController)
                    }
                    // 中央 +
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        FloatingActionButton(
                            onClick = { showAddSheet = true },
                            modifier = Modifier.size(58.dp),
                            shape = CircleShape,
                            containerColor = dev.dzsun.bookkeeping.core.designsystem.BrandBlue,
                            contentColor = androidx.compose.ui.graphics.Color.White,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "记一笔")
                        }
                    }
                    // 右侧两个
                    tabs.drop(2).forEach { tab ->
                        TabItem(tab, currentRoute, navController)
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LEDGER,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LEDGER) {
                LedgerScreen(
                    onAddEntry = { showAddSheet = true },
                    onEntryClick = { journalId ->
                        navController.navigate(Routes.entryDetail(journalId))
                    },
                    onAskClick = { navController.navigate(Routes.ASK) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(onAskClick = { navController.navigate(Routes.ASK) })
            }
            composable(Routes.DISCOVER) {
                DiscoverScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onImportClick = { navController.navigate(Routes.IMPORT) },
                )
            }
            composable(Routes.ASK) {
                dev.dzsun.bookkeeping.feature.ask.AskScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.IMPORT) {
                dev.dzsun.bookkeeping.feature.importer.ImportScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.ENTRY_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("journalId") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
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

@Composable
private fun RowScope.TabItem(
    tab: TabSpec,
    currentRoute: String?,
    navController: androidx.navigation.NavHostController,
) {
    val selected = currentRoute == tab.route
    NavigationBarItem(
        selected = selected,
        onClick = {
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        icon = {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                contentDescription = tab.label,
            )
        },
        label = { Text(tab.label) },
    )
}
