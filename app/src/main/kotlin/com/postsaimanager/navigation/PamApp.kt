package com.postsaimanager.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.postsaimanager.feature.documents.DocumentsScreen
import com.postsaimanager.feature.home.HomeScreen
import com.postsaimanager.feature.profiles.ProfilesScreen
import com.postsaimanager.feature.settings.SettingsScreen

/**
 * Root composable for the app.
 * Manages bottom navigation and the main NavHost.
 */
@Composable
fun PamApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show bottom bar only for top-level destinations
    val topLevelRoutes = TopLevelDestination.entries.map { it.route }
    val shouldShowBottomBar = currentRoute in topLevelRoutes

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = shouldShowBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                PamBottomNavigationBar(
                    currentRoute = currentRoute,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onDocumentClick = { /* TODO: nav to detail */ },
                    onScanClick = { /* TODO: nav to scanner */ },
                )
            }
            composable(TopLevelDestination.DOCUMENTS.route) {
                DocumentsScreen(
                    onDocumentClick = { /* TODO: nav to detail */ },
                )
            }
            composable(TopLevelDestination.PROFILES.route) {
                ProfilesScreen(
                    onProfileClick = { /* TODO: nav to profile detail */ },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun PamBottomNavigationBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = currentRoute == destination.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}
