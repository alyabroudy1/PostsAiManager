package com.postsaimanager.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.postsaimanager.R
import com.postsaimanager.core.designsystem.icon.PamIcons

/**
 * Top-level destinations in the bottom navigation bar.
 */
enum class TopLevelDestination(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    @StringRes val labelRes: Int,
    val route: String,
) {
    HOME(
        selectedIcon = PamIcons.Home,
        unselectedIcon = PamIcons.HomeOutlined,
        labelRes = R.string.nav_home,
        route = "home",
    ),
    DOCUMENTS(
        selectedIcon = PamIcons.Documents,
        unselectedIcon = PamIcons.DocumentsOutlined,
        labelRes = R.string.nav_documents,
        route = "documents",
    ),
    PROFILES(
        selectedIcon = PamIcons.Profiles,
        unselectedIcon = PamIcons.ProfilesOutlined,
        labelRes = R.string.nav_profiles,
        route = "profiles",
    ),
    SETTINGS(
        selectedIcon = PamIcons.Settings,
        unselectedIcon = PamIcons.SettingsOutlined,
        labelRes = R.string.nav_settings,
        route = "settings",
    ),
    // PARSER removed 2026-08-07 — Arabic syntax analysis is out of scope.
    // The freed fifth slot is reserved for ASSISTANT (Phase 10.4.2).
}
