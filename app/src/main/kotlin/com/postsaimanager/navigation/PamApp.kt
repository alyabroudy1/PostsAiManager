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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.postsaimanager.feature.chat.ChatScreen
import com.postsaimanager.feature.documents.DocumentDetailScreen
import com.postsaimanager.feature.documents.DocumentsScreen
import com.postsaimanager.feature.home.HomeScreen
import com.postsaimanager.feature.profiles.ProfilesScreen
import com.postsaimanager.feature.scanner.ScannerScreen
import com.postsaimanager.feature.settings.SettingsScreen
import androidx.navigation.NavGraph.Companion.findStartDestination

@Composable
fun PamApp() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

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
            // ── Top-level destinations ──
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onDocumentClick = { id ->
                        navController.navigate("document/$id")
                    },
                    onScanClick = {
                        navController.navigate("scanner")
                    },
                )
            }
            composable(TopLevelDestination.DOCUMENTS.route) {
                DocumentsScreen(
                    onDocumentClick = { id ->
                        navController.navigate("document/$id")
                    },
                )
            }
            composable(TopLevelDestination.PROFILES.route) {
                ProfilesScreen(
                    onProfileClick = { /* TODO: profile detail */ },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen()
            }

            // ── Detail destinations ──
            composable(
                route = "document/{documentId}",
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) {
                DocumentDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onChatClick = { docId ->
                        navController.navigate("chat?documentId=$docId")
                    },
                )
            }

            composable("scanner") {
                ScannerScreen(
                    onScanComplete = { documentId ->
                        navController.navigate("document/$documentId") {
                            popUpTo("scanner") { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "chat?documentId={documentId}",
                arguments = listOf(
                    navArgument("documentId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ChatScreen(
                    documentId = it.arguments?.getString("documentId"),
                    onNavigateBack = { navController.popBackStack() },
                )
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
