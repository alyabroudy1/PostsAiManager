package com.postsaimanager.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.ConfigSpecItem
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.AppLockTimeouts
import com.postsaimanager.core.model.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onManageModelsClick: () -> Unit = {},
    onRecentlyDeletedClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val inferenceSettings by viewModel.inferenceSettings.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    val appLockNotice by viewModel.appLockNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

            // ── AI ──
            SettingsSectionHeader("AI")
            SettingsClickItem(
                icon = PamIcons.AiModel,
                title = "AI models",
                subtitle = "Download and manage on-device models",
                onClick = onManageModelsClick,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── On-device AI ──
            SettingsSectionHeader("On-device AI")
            if (inferenceSettings.schema.isEmpty()) {
                Text(
                    text = "Install a model to configure it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                inferenceSettings.schema.forEach { spec ->
                    ConfigSpecItem(
                        spec = spec,
                        overrides = inferenceSettings.overrides,
                        onValueChange = { value -> viewModel.setInferenceSetting(spec.key, value) },
                    )
                }
                SettingsClickItem(
                    icon = PamIcons.Settings,
                    title = "Reset to defaults",
                    subtitle = "Clear every custom AI setting above",
                    onClick = viewModel::resetInference,
                )
            }

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
            SettingsClickItem(
                icon = PamIcons.Delete,
                title = "Recently deleted",
                subtitle = "Restore or permanently delete documents",
                onClick = onRecentlyDeletedClick,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Security ──
            SettingsSectionHeader("Security")
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = "App lock",
                subtitle = "Ask for your fingerprint, face, PIN or pattern to open the app",
                checked = prefs.biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )
            if (prefs.biometricEnabled) {
                SettingsClickItem(
                    icon = PamIcons.Settings,
                    title = "Lock after",
                    subtitle = lockTimeoutLabel(prefs.appLockTimeoutMinutes),
                    onClick = { showLockTimeoutDialog = true },
                )
            }

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

    if (showLockTimeoutDialog) {
        val options = AppLockTimeouts.OPTIONS_MINUTES
        ChoiceDialog(
            title = "Lock after",
            options = options.map(::lockTimeoutLabel),
            selectedIndex = options.indexOf(prefs.appLockTimeoutMinutes).coerceAtLeast(0),
            onSelect = { index ->
                viewModel.setAppLockTimeoutMinutes(options[index])
                showLockTimeoutDialog = false
            },
            onDismiss = { showLockTimeoutDialog = false },
        )
    }

    appLockNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAppLockNotice,
            title = { Text("App lock is not available yet") },
            text = {
                Text(
                    when (notice) {
                        AppLockNotice.NotEnrolled ->
                            "This phone has no screen lock or fingerprint/face set up, so there is " +
                                "nothing to unlock the app with. Set one up in the system " +
                                "security settings, then come back and turn App lock on."
                        AppLockNotice.Unavailable ->
                            "This device cannot check your fingerprint, face or screen lock right " +
                                "now, so App lock cannot be turned on."
                        AppLockNotice.AuthenticationFailed ->
                            "Your identity could not be confirmed, so App lock stays off. Try again."
                    },
                )
            },
            confirmButton = {
                if (notice == AppLockNotice.NotEnrolled) {
                    TextButton(
                        onClick = {
                            viewModel.dismissAppLockNotice()
                            openSecuritySettings(context)
                        },
                    ) { Text("Open security settings") }
                } else {
                    TextButton(onClick = viewModel::dismissAppLockNotice) { Text("OK") }
                }
            },
            dismissButton = if (notice == AppLockNotice.NotEnrolled) {
                { TextButton(onClick = viewModel::dismissAppLockNotice) { Text("Not now") } }
            } else {
                null
            },
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

private const val BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL = 0x0000000F or 0x00008000

private fun lockTimeoutLabel(minutes: Int): String = when (minutes) {
    0 -> "Immediately"
    1 -> "After 1 minute"
    else -> "After $minutes minutes"
}

/**
 * Sends the user to where a screen lock or biometric can be set up. Android 11+ has a direct
 * enrolment screen; older versions get the general security page, and a device with neither
 * (rare OEM builds) falls back to the top-level settings rather than doing nothing.
 */
private fun openSecuritySettings(context: Context) {
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    // BiometricManager.Authenticators.BIOMETRIC_STRONG | DEVICE_CREDENTIAL,
                    // spelled out so this module needs no androidx.biometric dependency.
                    BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL,
                ),
            )
        }
        add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        add(Intent(Settings.ACTION_SETTINGS))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, more general, screen.
        }
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

