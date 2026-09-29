package com.zohaib.batterymonitor.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zohaib.batterymonitor.App
import com.zohaib.batterymonitor.service.MonitorService

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notificationsGranted(this)) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        MonitorService.start(this)
        val start = if (App.instance.prefs.onboarded) "home" else "onboarding"
        setContent { BatteryTheme { AppNav(start) } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("apps", "Apps", Icons.Outlined.Apps),
    Tab("tips", "Tips", Icons.Outlined.Lightbulb),
    Tab("cleaner", "Cleaner", Icons.Outlined.CleaningServices),
)

@Composable
fun AppNav(start: String) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("onboarding") {
                OnboardingScreen(onDone = {
                    App.instance.prefs.onboarded = true
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                })
            }
            composable("home") {
                HomeScreen(
                    onOpenSessions = { type -> nav.navigate("sessions/$type") },
                    onOpenApps = { nav.navigate("apps") { launchSingleTop = true } },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("sessions/{type}", arguments = listOf(navArgument("type") { type = NavType.StringType })) {
                SessionsScreen(
                    type = it.arguments?.getString("type") ?: "charge",
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate("session/$id") },
                )
            }
            composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                SessionDetailScreen(id = it.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
            }
            composable("apps") { AppsScreen() }
            composable("tips") { TipsScreen() }
            composable("cleaner") { CleanerScreen() }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
