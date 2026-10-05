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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.feature.setup.SetupScreen
import kotlinx.coroutines.launch
import com.postsaimanager.feature.documents.DocumentUndoViewModel
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.postsaimanager.feature.chat.ChatScreen
import com.postsaimanager.feature.chat.ChatViewModel
import com.postsaimanager.feature.chat.ChatSource
import com.postsaimanager.feature.documents.DocumentDetailScreen
import com.postsaimanager.feature.documents.DocumentsScreen
import com.postsaimanager.feature.documents.TrashScreen
import com.postsaimanager.feature.home.HomeScreen
import com.postsaimanager.feature.profiles.ProfileDetailScreen
import com.postsaimanager.feature.profiles.ProfileDetailViewModel
import com.postsaimanager.feature.profiles.ProfilesScreen
import com.postsaimanager.feature.scanner.ScannerScreen
import com.postsaimanager.feature.models.ModelsScreen
import com.postsaimanager.feature.settings.SettingsScreen
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * The app's navigation. Opens on the first-run setup when no chat model is installed yet (and the user has not postponed it),
 * otherwise on Home; nothing is drawn until that is known.
 */
@Composable
fun PamApp(formFillingEnabled: Boolean) {
    val startup: StartupViewModel = hiltViewModel()
    val startRoute by startup.startRoute.collectAsStateWithLifecycle()
    startRoute?.let { PamNavigation(startRoute = it, formFillingEnabled = formFillingEnabled) }
}

@Composable
private fun PamNavigation(startRoute: String, formFillingEnabled: Boolean) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    // App-level scope and host: the "moved to Recently deleted / Undo" snackbar has to
    // outlive the detail screen that triggered it, so it is shown here, on the screen
    // the user lands on.
    val scope = rememberCoroutineScope()
    val undoViewModel: DocumentUndoViewModel = hiltViewModel()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // A tapped notification: applied once, here, which only composes after the app lock is open.
    val notificationRoutes: NotificationRouteViewModel = hiltViewModel()
    val pendingNotification by notificationRoutes.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingNotification) {
        val pending = pendingNotification ?: return@LaunchedEffect
        val target = notificationRoutes.resolve(pending)
        notificationRoutes.consume(pending)
        navController.navigate(target) {
            launchSingleTop = true
            if (target == StartRoutes.HOME) popUpTo(navController.graph.findStartDestination().id)
        }
    }

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
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ── First-run AI model setup (no bottom bar: not a top-level destination) ──
            composable(StartRoutes.SETUP) {
                SetupScreen(
                    onDone = {
                        navController.navigate(TopLevelDestination.HOME.route) {
                            popUpTo(StartRoutes.SETUP) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            // ── Top-level destinations ──
            composable(TopLevelDestination.HOME.route) {
                HomeScreen(
                    onDocumentClick = { id ->
                        navController.navigate("document/$id")
                    },
                    onScanClick = {
                        navController.navigate("scanner")
                    },
                    onAskAcrossDocumentsClick = {
                        navController.navigate("chat")
                    },
                    onInstallModelClick = {
                        navController.navigate(StartRoutes.SETUP)
                    },
                    onDownloadsClick = {
                        navController.navigate("models")
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
                    onProfileClick = { id -> navController.navigate("profile/$id") },
                    onAddPerson = { navController.navigate("profile/${ProfileDetailViewModel.NEW}") },
                )
            }
            composable(
                route = "profile/{${ProfileDetailViewModel.ARG_PROFILE_ID}}",
                arguments = listOf(navArgument(ProfileDetailViewModel.ARG_PROFILE_ID) { type = NavType.StringType }),
            ) {
                ProfileDetailScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onManageModelsClick = { navController.navigate("models") },
                    onRecentlyDeletedClick = { navController.navigate("trash") },
                )
            }

            composable("models") {
                ModelsScreen(onNavigateBack = { navController.popBackStack() }, showFormFillingNote = formFillingEnabled)
            }

            composable("trash") {
                TrashScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onDocumentClick = { id -> navController.navigate("document/$id") },
                )
            }

            // ── Detail destinations ──
            composable(
                // `page` is optional and 1-based — set only when arriving from a chat
                // citation chip (4.3), so DocumentDetailScreen can jump straight to it.
                route = "document/{documentId}?page={page}",
                arguments = listOf(
                    navArgument("documentId") { type = NavType.StringType },
                    navArgument("page") {
                        type = NavType.IntType
                        defaultValue = NO_INITIAL_PAGE
                    },
                ),
            ) { backStackEntry ->
                val page = backStackEntry.arguments?.getInt("page") ?: NO_INITIAL_PAGE
                DocumentDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onChatClick = { docId ->
                        navController.navigate("chat?documentId=$docId")
                    },
                    // "Help me fill it" / "Fill in this form": the same document chat, with the form fill started.
                    onFillForm = if (formFillingEnabled) { docId ->
                        navController.navigate("chat?documentId=$docId&${ChatViewModel.ARG_FILL}=true")
                    } else null,
                    onDeleted = { docId ->
                        navController.popBackStack()
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            val result = snackbarHostState.showSnackbar(
                                message = "Document moved to Recently deleted",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) undoViewModel.restore(docId)
                        }
                    },
                    initialPage = page.takeIf { it != NO_INITIAL_PAGE },
                    onInstallModel = { navController.navigate(StartRoutes.SETUP) },
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
                route = "chat?documentId={documentId}&${ChatViewModel.ARG_FILL}={${ChatViewModel.ARG_FILL}}",
                arguments = listOf(
                    navArgument("documentId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    // Set only by "Help me fill it": the chat opens with the form fill started.
                    navArgument(ChatViewModel.ARG_FILL) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) {
                ChatScreen(
                    documentId = it.arguments?.getString("documentId"),
                    onNavigateBack = { navController.popBackStack() },
                    onManageModelsClick = { navController.navigate("models") },
                    onSourceClick = { source -> navController.navigate(source.toRoute()) },
                )
            }
        }
    }
}

/** A tapped citation chip's own document, at its page — see the `document/{documentId}?page={page}` route. */
private fun ChatSource.toRoute(): String =
    pageNumber?.let { "document/$documentId?page=$it" } ?: "document/$documentId"

/** [NavType.IntType] cannot express "absent" with `null`, so this stands in for it. */
private const val NO_INITIAL_PAGE = -1

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
