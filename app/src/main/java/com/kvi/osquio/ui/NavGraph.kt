package com.kvi.osquio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kvi.osquio.data.model.User
import com.kvi.osquio.ui.chat.ChatScreen
import com.kvi.osquio.ui.chat.ChatViewModel
import com.kvi.osquio.ui.history.HistoryScreen
import com.kvi.osquio.ui.rankings.RankingsScreen
import com.kvi.osquio.ui.settings.SettingsScreen
import com.kvi.osquio.ui.stats.StatsScreen
import com.kvi.osquio.ui.summon.SummonScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("summon", "Beacon", Icons.Default.Notifications),
    Tab("stats", "Stats", Icons.Default.Person),
    Tab("rankings", "Rankings", Icons.Default.EmojiEvents),
    Tab("history", "History", Icons.Default.DateRange),
    Tab("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
)

@Composable
fun MainNavGraph(currentUser: User, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val chatVm: ChatViewModel = viewModel()
    val hasUnread by chatVm.hasUnread.collectAsState()
    LaunchedEffect(Unit) { chatVm.load() }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab.route == "chat") {
                                BadgedBox(badge = { if (hasUnread) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "summon",
            modifier = Modifier.padding(padding),
        ) {
            val onGoToSettings: () -> Unit = {
                navController.navigate("settings") {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = false
                }
            }
            val onBackFromSettings: () -> Unit = { navController.popBackStack() }
            composable("summon") { SummonScreen(currentUser) }
            composable("stats") { StatsScreen(onNavigateToSettings = onGoToSettings) }
            composable("rankings") { RankingsScreen(onNavigateToSettings = onGoToSettings) }
            composable("history") { HistoryScreen(onNavigateToSettings = onGoToSettings) }
            composable("chat") { ChatScreen(currentUser = currentUser, onNavigateToSettings = onGoToSettings, vm = chatVm) }
            composable("settings") { SettingsScreen(currentUser, onSignOut, onBack = onBackFromSettings) }
        }
    }
}
