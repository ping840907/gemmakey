package com.gemmakey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.*
import com.gemmakey.ai.AppSettings
import com.gemmakey.ui.screens.ChatScreen
import com.gemmakey.ui.screens.HistoryScreen
import com.gemmakey.ui.screens.OnboardingScreen
import com.gemmakey.ui.screens.SettingsScreen
import com.gemmakey.ui.screens.StatisticsScreen
import com.gemmakey.ui.theme.TanQianTheme
import com.gemmakey.viewmodel.ChatViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TanQianTheme {
                var showOnboarding by remember { mutableStateOf(!appSettings.onboardingCompleted) }

                if (showOnboarding) {
                    OnboardingScreen(
                        onComplete = { showOnboarding = false }
                    )
                } else {
                    MoneyTalksApp()
                }
            }
        }
    }
}

private sealed class NavDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
) {
    data object Chat : NavDestination(
        "chat", R.string.tab_chat,
        Icons.Default.ChatBubbleOutline, Icons.Default.ChatBubble
    )
    data object History : NavDestination(
        "history", R.string.tab_history,
        Icons.Default.ListAlt, Icons.Default.List
    )
    data object Statistics : NavDestination(
        "stats", R.string.tab_stats,
        Icons.Default.BarChart, Icons.Default.BarChart
    )
    data object Settings : NavDestination(
        "settings", R.string.tab_settings,
        Icons.Default.SettingsApplications, Icons.Default.Settings
    )

    companion object {
        val all = listOf(Chat, History, Statistics, Settings)
    }
}

@Composable
private fun MoneyTalksApp() {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

    // Shared ChatViewModel so Settings can trigger reinitialize()
    val chatViewModel: ChatViewModel = hiltViewModel()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavDestination.all.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(NavDestination.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) dest.selectedIcon else dest.icon,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(dest.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = NavDestination.Chat.route,
            modifier         = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(NavDestination.Chat.route)       { ChatScreen(chatViewModel) }
            composable(NavDestination.History.route)    { HistoryScreen() }
            composable(NavDestination.Statistics.route) { StatisticsScreen() }
            composable(NavDestination.Settings.route) {
                SettingsScreen(
                    onSaved = {
                        chatViewModel.reinitialize()
                        navController.navigate(NavDestination.Chat.route) {
                            popUpTo(NavDestination.Chat.route) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
