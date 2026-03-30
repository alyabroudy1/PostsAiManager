package com.postsaimanager.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            PamTopAppBar(title = "Settings")
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // AI Section
            SettingsSectionHeader("AI Configuration")
            SettingsItem(
                title = "AI Models",
                subtitle = "Download and manage local AI models",
                icon = PamIcons.AiModel,
                onClick = { /* TODO: Navigate to AI Models */ },
            )
            SettingsItem(
                title = "Default Model",
                subtitle = "None selected",
                onClick = { /* TODO */ },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Appearance Section
            SettingsSectionHeader("Appearance")
            SettingsItem(
                title = "Theme",
                subtitle = "System default",
                onClick = { /* TODO */ },
            )
            SettingsItem(
                title = "Language",
                subtitle = "Deutsch",
                onClick = { /* TODO */ },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About Section
            SettingsSectionHeader("About")
            SettingsItem(
                title = "Version",
                subtitle = "0.1.0",
                onClick = { },
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = if (icon != null) {
            { Icon(imageVector = icon, contentDescription = null) }
        } else null,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
