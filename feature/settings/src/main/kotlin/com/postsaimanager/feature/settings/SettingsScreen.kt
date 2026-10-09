package com.postsaimanager.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.ConfigSpecItem
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.AppLanguage
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
    var showOpenSourceDialog by remember { mutableStateOf(false) }
    val appLockNotice by viewModel.appLockNotice.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { PamTopAppBar(title = stringResource(R.string.settings_title)) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Appearance ──
            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(themeLabel(prefs.theme)),
                onClick = { showThemeDialog = true },
            )
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(languageLabel(language)),
                onClick = { showLanguageDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── AI ──
            SettingsSectionHeader(stringResource(R.string.settings_section_ai))
            SettingsClickItem(
                icon = PamIcons.AiModel,
                title = stringResource(R.string.settings_ai_models),
                subtitle = stringResource(R.string.settings_ai_models_subtitle),
                onClick = onManageModelsClick,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── On-device AI ──
            SettingsSectionHeader(stringResource(R.string.settings_section_on_device_ai))
            if (inferenceSettings.schema.isEmpty()) {
                Text(
                    text = stringResource(if (inferenceSettings.loaded) R.string.settings_install_model_hint else R.string.settings_loading_model),
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
                    title = stringResource(R.string.settings_reset_defaults),
                    subtitle = stringResource(R.string.settings_reset_defaults_subtitle),
                    onClick = viewModel::resetInference,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Processing ──
            SettingsSectionHeader(stringResource(R.string.settings_section_processing))
            SettingsSwitchItem(
                icon = PamIcons.AiModel,
                title = stringResource(R.string.settings_auto_process),
                subtitle = stringResource(R.string.settings_auto_process_subtitle),
                checked = prefs.autoProcessAfterScan,
                onCheckedChange = viewModel::setAutoProcess,
            )
            SettingsSwitchItem(
                icon = PamIcons.AiModel,
                title = stringResource(R.string.settings_update_older_title),
                subtitle = stringResource(R.string.settings_update_older_subtitle),
                checked = prefs.updateOlderLettersAutomatically,
                onCheckedChange = viewModel::setUpdateOlderLettersAutomatically,
            )
            SettingsClickItem(
                icon = PamIcons.Delete,
                title = stringResource(R.string.settings_recently_deleted),
                subtitle = stringResource(R.string.settings_recently_deleted_subtitle),
                onClick = onRecentlyDeletedClick,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Security ──
            SettingsSectionHeader(stringResource(R.string.settings_section_security))
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_app_lock_title),
                subtitle = stringResource(R.string.settings_app_lock_subtitle),
                checked = prefs.biometricEnabled,
                onCheckedChange = viewModel::setBiometricEnabled,
            )
            if (prefs.biometricEnabled) {
                SettingsClickItem(
                    icon = PamIcons.Settings,
                    title = stringResource(R.string.settings_lock_after_title),
                    subtitle = lockTimeoutLabel(context, prefs.appLockTimeoutMinutes),
                    onClick = { showLockTimeoutDialog = true },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Notifications ──
            SettingsSectionHeader(stringResource(R.string.settings_section_notifications))
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_deadline_reminders),
                subtitle = stringResource(R.string.settings_deadline_reminders_subtitle),
                checked = prefs.notificationsEnabled,
                onCheckedChange = viewModel::setNotificationsEnabled,
            )
            SettingsSwitchItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_reading_finished_title),
                subtitle = stringResource(R.string.settings_reading_finished_subtitle),
                checked = prefs.readingFinishedNotifications,
                onCheckedChange = viewModel::setReadingFinishedNotifications,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Debug: a debug build only; nothing is shown in a release build ──
            val debuggable = remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
            if (debuggable) {
                val gemmaTrial: GemmaTrialViewModel = hiltViewModel()
                val trialOn by gemmaTrial.enabled.collectAsStateWithLifecycle()
                SettingsSectionHeader(stringResource(R.string.settings_debug_section))
                SettingsSwitchItem(
                    icon = PamIcons.AiModel,
                    title = stringResource(R.string.settings_gemma_trial_title),
                    subtitle = stringResource(R.string.settings_gemma_trial_subtitle),
                    checked = trialOn,
                    onCheckedChange = gemmaTrial::setEnabled,
                )
                val readerStyle: GemmaReaderStyleViewModel = hiltViewModel()
                val questionsOn by readerStyle.questions.collectAsStateWithLifecycle()
                SettingsSwitchItem(
                    icon = PamIcons.AiModel,
                    title = stringResource(R.string.settings_reader_style_title),
                    subtitle = stringResource(R.string.settings_reader_style_subtitle),
                    checked = questionsOn,
                    onCheckedChange = readerStyle::setQuestions,
                )
                val alwaysImage by readerStyle.alwaysImage.collectAsStateWithLifecycle()
                SettingsSwitchItem(
                    icon = PamIcons.AiModel,
                    title = stringResource(R.string.settings_questions_image_title),
                    subtitle = stringResource(R.string.settings_questions_image_subtitle),
                    checked = alwaysImage,
                    onCheckedChange = readerStyle::setAlwaysImage,
                )
                val readingAccelerator: ReadingAcceleratorViewModel = hiltViewModel()
                val readingOnGpu by readingAccelerator.onGpu.collectAsStateWithLifecycle()
                SettingsSwitchItem(
                    icon = PamIcons.AiModel,
                    title = stringResource(R.string.settings_reading_accelerator_title),
                    subtitle = stringResource(R.string.settings_reading_accelerator_subtitle),
                    checked = readingOnGpu,
                    onCheckedChange = readingAccelerator::setOnGpu,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── About ──
            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_version),
                subtitle = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                },
                onClick = {},
            )
            SettingsClickItem(
                icon = PamIcons.Settings,
                title = stringResource(R.string.settings_open_source_title),
                subtitle = stringResource(R.string.settings_open_source_subtitle),
                onClick = { showOpenSourceDialog = true },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Theme dialog
    if (showThemeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = AppTheme.entries.map { stringResource(themeLabel(it)) },
            selectedIndex = AppTheme.entries.indexOf(prefs.theme),
            onSelect = { index ->
                viewModel.setTheme(AppTheme.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showOpenSourceDialog) {
        AlertDialog(
            onDismissRequest = { showOpenSourceDialog = false },
            title = { Text(stringResource(R.string.settings_open_source_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings_address_data_attribution))
                    Text(stringResource(R.string.settings_litert_attribution))
                    Text(stringResource(R.string.settings_skills_attribution))
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceDialog = false }) { Text(stringResource(R.string.settings_ok)) }
            },
        )
    }

    if (showLockTimeoutDialog) {
        val options = AppLockTimeouts.OPTIONS_MINUTES
        ChoiceDialog(
            title = stringResource(R.string.settings_lock_after_title),
            options = options.map { lockTimeoutLabel(context, it) },
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
            title = { Text(stringResource(R.string.settings_app_lock_notice_title)) },
            text = {
                Text(
                    stringResource(
                        when (notice) {
                            AppLockNotice.NotEnrolled -> R.string.settings_app_lock_not_enrolled
                            AppLockNotice.Unavailable -> R.string.settings_app_lock_unavailable
                            AppLockNotice.AuthenticationFailed -> R.string.settings_app_lock_auth_failed
                        },
                    ),
                )
            },
            confirmButton = {
                if (notice == AppLockNotice.NotEnrolled) {
                    TextButton(
                        onClick = {
                            viewModel.dismissAppLockNotice()
                            viewModel.onOpeningSecuritySettings()
                            if (!openSecuritySettings(context)) viewModel.onSecuritySettingsLaunchFailed()
                        },
                    ) { Text(stringResource(R.string.settings_open_security_settings)) }
                } else {
                    TextButton(onClick = viewModel::dismissAppLockNotice) { Text(stringResource(R.string.settings_ok)) }
                }
            },
            dismissButton = if (notice == AppLockNotice.NotEnrolled) {
                { TextButton(onClick = viewModel::dismissAppLockNotice) { Text(stringResource(R.string.settings_not_now)) } }
            } else {
                null
            },
        )
    }

    // Language dialog
    if (showLanguageDialog) {
        val languages = AppLanguage.entries
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = languages.map { stringResource(languageLabel(it)) },
            selectedIndex = languages.indexOf(language),
            onSelect = { index ->
                showLanguageDialog = false
                // The app recreates its screens in the new language: nothing more to do here.
                viewModel.setLanguage(languages[index])
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@StringRes
private fun themeLabel(theme: AppTheme): Int = when (theme) {
    AppTheme.SYSTEM -> R.string.settings_theme_system
    AppTheme.LIGHT -> R.string.settings_theme_light
    AppTheme.DARK -> R.string.settings_theme_dark
}

/** The language names are written in their own language (Deutsch, العربية, English), so a person can find theirs whatever the app shows. */
@StringRes
private fun languageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.GERMAN -> R.string.settings_language_german
    AppLanguage.ARABIC -> R.string.settings_language_arabic
    AppLanguage.ENGLISH -> R.string.settings_language_english
}

private const val BIOMETRIC_STRONG_OR_DEVICE_CREDENTIAL = 0x0000000F or 0x00008000

private fun lockTimeoutLabel(context: Context, minutes: Int): String =
    if (minutes == 0) {
        context.getString(R.string.settings_lock_immediately)
    } else {
        context.resources.getQuantityString(R.plurals.settings_lock_after_minutes, minutes, minutes)
    }

/**
 * Sends the user to where a screen lock or biometric can be set up. Android 11+ has a direct
 * enrolment screen; older versions get the general security page, and a device with neither
 * (rare OEM builds) falls back to the top-level settings rather than doing nothing.
 */
private fun openSecuritySettings(context: Context): Boolean {
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
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next, more general, screen.
        }
    }
    return false
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

