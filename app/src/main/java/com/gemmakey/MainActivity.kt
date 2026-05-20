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
import androidx.navigation.compose.*
import com.gemmakey.ui.screens.ChatScreen
import com.gemmakey.ui.screens.HistoryScreen
import com.gemmakey.ui.screens.StatisticsScreen
import com.gemmakey.ui.theme.GemmaKeyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmaKeyTheme {
                GemmaKeyApp()
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

    companion object {
        val all = listOf(Chat, History, Statistics)
    }
}

@Composable
private fun GemmaKeyApp() {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

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
            navController = navController,
            startDestination = NavDestination.Chat.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)  // prevent imePadding() double-counting nav bar
        ) {
            composable(NavDestination.Chat.route)       { ChatScreen() }
            composable(NavDestination.History.route)    { HistoryScreen() }
            composable(NavDestination.Statistics.route) { StatisticsScreen() }
        }
    }
}
