package com.postsaimanager.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { PamTopAppBar(title = "Settings") },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Appearance ──
            SettingsSectionHeader("Appearance")
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = "Theme",
                subtitle = prefs.theme.name.lowercase().replaceFirstChar { it.uppercase() },
                onClick = { showThemeDialog = true },
            )
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = "Language",
                subtitle = when (prefs.defaultLanguage) {
                    "de" -> "German"
                    "ar" -> "Arabic"
                    else -> "English"
                },
                onClick = { showLanguageDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Processing ──
            SettingsSectionHeader("Document Processing")
            SettingsSwitchItem(
                icon = PamIcons.AiModel,
                title = "Auto-process after scan",
                subtitle = "Run OCR and extraction automatically",
                checked = prefs.autoProcessAfterScan,
                onCheckedChange = viewModel::setAutoProcess,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Security ──
            SettingsSectionHeader("Security")
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = "Biometric lock",
                subtitle = "Require fingerprint or face to open app",
                checked = prefs.biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Notifications ──
            SettingsSectionHeader("Notifications")
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = "Deadline reminders",
                subtitle = "Get notified about upcoming deadlines",
                checked = prefs.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── About ──
            SettingsSectionHeader("About")
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = "Version",
                subtitle = "1.0.0 (Phase 1)",
                onClick = {},
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Theme dialog
    if (showThemeDialog) {
        ChoiceDialog(
            title = "Theme",
            options = AppTheme.entries.map {
                it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            },
            selectedIndex = AppTheme.entries.indexOf(prefs.theme),
            onSelect = { index ->
                viewModel.setTheme(AppTheme.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    // Language dialog
    if (showLanguageDialog) {
        val languages = listOf("German" to "de", "Arabic" to "ar", "English" to "en")
        ChoiceDialog(
            title = "Default Language",
            options = languages.map { it.first },
            selectedIndex = languages.indexOfFirst { it.second == prefs.defaultLanguage }.coerceAtLeast(0),
            onSelect = { index ->
                viewModel.setDefaultLanguage(languages[index].second)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
