package com.postsaimanager.feature.settings

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.backup.BackupProgress
import com.postsaimanager.core.domain.backup.BackupStage
import com.postsaimanager.core.domain.backup.BackupStatus
import com.postsaimanager.core.domain.backup.DriveAccount
import com.postsaimanager.core.domain.backup.RemoteBackup
import java.text.DateFormat
import java.util.Date

/**
 * The "Backup" section of Settings: Google Drive account, automatic daily backup (Wi-Fi only by default), "Back up now" with progress,
 * "Restore" with a confirmation, and the last result. It owns its own view model, so the rest of the screen does not know it exists.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Agreed: ask again, and Google now answers with the access itself. Cancelled: stay disconnected.
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect()
    }
    LaunchedEffect(ui.consent) {
        ui.consent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            viewModel.consentLaunched()
        }
    }

    val current = overview
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    BackupHeader(stringResource(R.string.backup_section))

    if (current == null) return
    val account = current.account
    if (account is DriveAccount.Connected) {
        BackupRow(
            title = stringResource(R.string.backup_account_title),
            subtitle = account.email ?: stringResource(R.string.backup_account_unknown),
            action = { TextButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.backup_disconnect)) } },
        )
        BackupSwitch(
            title = stringResource(R.string.backup_auto_title),
            subtitle = stringResource(R.string.backup_auto_subtitle),
            checked = current.settings.autoBackup,
            onCheckedChange = viewModel::setAuto,
        )
        BackupSwitch(
            title = stringResource(R.string.backup_wifi_title),
            subtitle = stringResource(R.string.backup_wifi_subtitle),
            checked = current.settings.wifiOnly,
            enabled = current.settings.autoBackup,
            onCheckedChange = viewModel::setWifi,
        )
        val busy = current.progress != null || ui.restore is RestoreStep.Restoring
        BackupRow(
            title = stringResource(R.string.backup_now_title),
            subtitle = lastResult(context, current.status),
            onClick = if (busy) null else viewModel::backUpNow,
        )
        current.progress?.let { ProgressLine(it) }
        BackupRow(
            title = stringResource(R.string.backup_restore_title),
            subtitle = stringResource(R.string.backup_restore_subtitle),
            onClick = if (busy) null else viewModel::openRestore,
        )
    } else {
        BackupRow(
            title = stringResource(R.string.backup_connect_title),
            subtitle = stringResource(R.string.backup_connect_subtitle),
            onClick = viewModel::connect,
        )
    }

    RestoreDialogs(ui.restore, viewModel)
    ui.notice?.let { NoticeDialog(it, viewModel::dismissNotice) }
}

@Composable
private fun BackupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun BackupRow(title: String, subtitle: String, onClick: (() -> Unit)? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PamIcons.Upload, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
private fun BackupSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    BackupRow(
        title = title,
        subtitle = subtitle,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        action = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

@Composable
private fun ProgressLine(progress: BackupProgress) {
    Column(modifier = Modifier.padding(horizontal = 56.dp, vertical = 4.dp)) {
        Text(stringResource(stageLabel(progress.stage)), style = MaterialTheme.typography.bodySmall)
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun stageLabel(stage: BackupStage): Int = when (stage) {
    BackupStage.PACKING -> R.string.backup_stage_packing
    BackupStage.UPLOADING -> R.string.backup_stage_uploading
    BackupStage.DOWNLOADING -> R.string.backup_stage_downloading
    BackupStage.RESTORING -> R.string.backup_stage_restoring
    BackupStage.CLEANING -> R.string.backup_stage_cleaning
}

@Composable
private fun lastResult(context: Context, status: BackupStatus): String {
    val success = status.lastSuccessAt
    val error = status.lastError
    val errorAt = status.lastErrorAt
    return when {
        error != null && (success == null || (errorAt ?: 0L) > success) -> stringResource(R.string.backup_last_error, error)
        success != null -> stringResource(R.string.backup_last_success, formatTime(success), Formatter.formatShortFileSize(context, status.lastSizeBytes ?: 0L))
        else -> stringResource(R.string.backup_never)
    }
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
private fun RestoreDialogs(step: RestoreStep, viewModel: BackupViewModel) {
    val context = LocalContext.current
    when (step) {
        RestoreStep.Hidden -> Unit
        RestoreStep.Loading -> WaitDialog(stringResource(R.string.backup_loading))
        RestoreStep.Restoring -> WaitDialog(stringResource(R.string.backup_stage_restoring))
        is RestoreStep.Pick -> AlertDialog(
            onDismissRequest = viewModel::closeRestore,
            title = { Text(stringResource(R.string.backup_pick_title)) },
            text = {
                if (step.backups.isEmpty()) {
                    Text(stringResource(R.string.backup_pick_empty))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(step.backups, key = { it.id }) { backup ->
                            Column(modifier = Modifier.fillMaxWidth().clickable { viewModel.choose(backup) }.padding(vertical = 8.dp)) {
                                Text(formatTime(backup.createdAt), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    describe(context, backup),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeRestore) { Text(stringResource(R.string.backup_cancel)) } },
        )
        is RestoreStep.Confirm -> AlertDialog(
            onDismissRequest = viewModel::closeRestore,
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_text, formatTime(step.backup.createdAt))) },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore(step.backup) }) { Text(stringResource(R.string.backup_confirm_action)) } },
            dismissButton = { TextButton(onClick = viewModel::closeRestore) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

private fun describe(context: Context, backup: RemoteBackup): String =
    listOfNotNull(backup.deviceName, Formatter.formatShortFileSize(context, backup.sizeBytes)).joinToString(" · ")

@Composable
private fun WaitDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(text)
            }
        },
    )
}

@Composable
private fun NoticeDialog(notice: BackupNotice, onDismiss: () -> Unit) {
    val text = when (notice) {
        BackupNotice.Queued -> stringResource(R.string.backup_notice_queued)
        BackupNotice.AuthRequired -> stringResource(R.string.backup_notice_auth)
        BackupNotice.NewerApp -> stringResource(R.string.backup_notice_newer_app)
        is BackupNotice.Failed -> stringResource(R.string.backup_notice_failed, notice.detail)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) } },
    )
}
