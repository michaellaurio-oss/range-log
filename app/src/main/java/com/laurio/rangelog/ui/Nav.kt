package com.laurio.rangelog.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("sessions", "Sessions", Icons.Default.DateRange),
    Tab("guns", "Guns", Icons.Default.Build),
    Tab("stats", "Stats", Icons.Default.Star),
)

@Composable
fun RangeLogNav() {
    val nav = rememberNavController()
    val mainVm: MainViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(Unit) {
        mainVm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "sessions", modifier = Modifier.padding(padding)) {
            composable("sessions") {
                SessionsScreen(mainVm, onOpen = { nav.navigate("session/$it") })
            }
            composable("guns") {
                GunsScreen(mainVm, onOpen = { nav.navigate("gun/$it") })
            }
            composable("stats") {
                StatsScreen(mainVm)
            }
            composable(
                "session/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                SessionEditScreen(onDone = { nav.popBackStack() })
            }
            composable(
                "gun/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                GunDetailScreen(
                    onDone = { nav.popBackStack() },
                    onOpenSession = { nav.navigate("session/$it") },
                )
            }
        }
    }
}
