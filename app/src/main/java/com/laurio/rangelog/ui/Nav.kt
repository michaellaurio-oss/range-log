package com.laurio.rangelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
    // After a delete, offer to take it back right away.
    LaunchedEffect(Unit) {
        mainVm.repo.undoOffers.collect { label ->
            val result = snackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) mainVm.undo()
        }
    }

    val reminder by mainVm.cleanReminder.collectAsState()
    reminder?.let { due ->
        // Pop-up-ad style: wide, loud, and only the buttons close it (back and outside taps do nothing).
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(4.dp, MaterialTheme.colorScheme.error),
            ) {
                Box {
                    IconButton(
                        onClick = { mainVm.dismissCleanReminder() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp, 40.dp, 24.dp, 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "CLEAN YOUR GUN!",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        due.forEach { s ->
                            Text(
                                "${s.gun.name}\n${s.dueDetail}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Button(
                            onClick = { mainVm.dismissCleanReminder() },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text("Close", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
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
