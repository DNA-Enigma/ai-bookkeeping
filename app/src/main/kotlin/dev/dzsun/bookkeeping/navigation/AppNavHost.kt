package dev.dzsun.bookkeeping.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.dzsun.bookkeeping.feature.entry.AddEntryScreen
import dev.dzsun.bookkeeping.feature.ledger.LedgerScreen
import dev.dzsun.bookkeeping.feature.settings.SettingsScreen
import dev.dzsun.bookkeeping.feature.stats.StatsScreen

object Routes {
    const val LEDGER = "ledger"
    const val ADD_ENTRY = "entry/new"
    const val STATS = "stats"
    const val SETTINGS = "settings"
}

private data class TabSpec(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val tabs = listOf(
    TabSpec(Routes.LEDGER, "首页", Icons.Filled.List, Icons.Outlined.List),
    TabSpec(Routes.ADD_ENTRY, "记一笔", Icons.Filled.AddCircle, Icons.Outlined.AddCircle),
    TabSpec(Routes.STATS, "统计", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    TabSpec(Routes.SETTINGS, "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
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
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LEDGER,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LEDGER) {
                LedgerScreen(onAddEntry = { navController.navigate(Routes.ADD_ENTRY) })
            }
            composable(Routes.ADD_ENTRY) {
                AddEntryScreen(onDone = {
                    navController.navigate(Routes.LEDGER) {
                        popUpTo(Routes.LEDGER) { inclusive = false }
                        launchSingleTop = true
                    }
                })
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
        }
    }
}
