package com.postsaimanager.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.postsaimanager.core.designsystem.icon.PamIcons

/**
 * Top-level destinations in the bottom navigation bar.
 */
enum class TopLevelDestination(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
    val route: String,
) {
    HOME(
        selectedIcon = PamIcons.Home,
        unselectedIcon = PamIcons.HomeOutlined,
        label = "Home",
        route = "home",
    ),
    DOCUMENTS(
        selectedIcon = PamIcons.Documents,
        unselectedIcon = PamIcons.DocumentsOutlined,
        label = "Documents",
        route = "documents",
    ),
    PROFILES(
        selectedIcon = PamIcons.Profiles,
        unselectedIcon = PamIcons.ProfilesOutlined,
        label = "Profiles",
        route = "profiles",
    ),
    SETTINGS(
        selectedIcon = PamIcons.Settings,
        unselectedIcon = PamIcons.SettingsOutlined,
        label = "Settings",
        route = "settings",
    ),
    PARSER(
        selectedIcon = PamIcons.AiModel,
        unselectedIcon = PamIcons.AiModel, // Assuming no outlined variant exists
        label = "Parser",
        route = "parser",
    ),
}
