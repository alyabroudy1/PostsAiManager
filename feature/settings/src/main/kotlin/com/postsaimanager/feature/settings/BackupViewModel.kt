package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.backup.ConsentRequest
import com.postsaimanager.core.domain.backup.BackupOverview
import com.postsaimanager.core.domain.backup.CloudResult
import com.postsaimanager.core.domain.backup.ConnectDriveUseCase
import com.postsaimanager.core.domain.backup.DisconnectDriveUseCase
import com.postsaimanager.core.domain.backup.DriveAuthorization
import com.postsaimanager.core.domain.backup.GetBackupStatusUseCase
import com.postsaimanager.core.domain.backup.ListBackupsUseCase
import com.postsaimanager.core.domain.backup.RemoteBackup
import com.postsaimanager.core.domain.backup.RequestBackupNowUseCase
import com.postsaimanager.core.domain.backup.RestoreBackupUseCase
import com.postsaimanager.core.domain.backup.RestoreOutcome
import com.postsaimanager.core.domain.backup.SetAutoBackupUseCase
import com.postsaimanager.core.domain.backup.SetBackupWifiOnlyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the restore sheet shows. */
sealed interface RestoreStep {
    data object Hidden : RestoreStep
    data object Loading : RestoreStep
    data class Pick(val backups: List<RemoteBackup>) : RestoreStep
    data class Confirm(val backup: RemoteBackup) : RestoreStep
    data object Restoring : RestoreStep
}

/** A one-time message for a dialog. */
sealed interface BackupNotice {
    data object Queued : BackupNotice
    data object AuthRequired : BackupNotice
    data object NewerApp : BackupNotice
    data class Failed(val detail: String) : BackupNotice
}

data class BackupUiState(
    /** A Google consent screen to launch now; [BackupViewModel.consentLaunched] clears it. */
    val consent: ConsentRequest? = null,
    val restore: RestoreStep = RestoreStep.Hidden,
    val notice: BackupNotice? = null,
)

/** The "Backup" section of Settings: connect Google Drive, the two switches, back up now, and restore. All logic is in domain use cases. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    getStatus: GetBackupStatusUseCase,
    private val connectDrive: ConnectDriveUseCase,
    private val disconnectDrive: DisconnectDriveUseCase,
    private val setAutoBackup: SetAutoBackupUseCase,
    private val setWifiOnly: SetBackupWifiOnlyUseCase,
    private val requestBackupNow: RequestBackupNowUseCase,
    private val listBackups: ListBackupsUseCase,
    private val restoreBackup: RestoreBackupUseCase,
) : ViewModel() {

    val overview: StateFlow<BackupOverview?> = getStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _ui = MutableStateFlow(BackupUiState())
    val ui: StateFlow<BackupUiState> = _ui.asStateFlow()

    /** Connects the account; also called again when the consent screen has returned an agreement. */
    fun connect() {
        viewModelScope.launch {
            when (val result = connectDrive()) {
                is DriveAuthorization.Granted -> Unit
                is DriveAuthorization.NeedsConsent -> _ui.update { it.copy(consent = result.request) }
                is DriveAuthorization.Failed -> _ui.update { it.copy(notice = BackupNotice.Failed(result.message)) }
            }
        }
    }

    fun consentLaunched() = _ui.update { it.copy(consent = null) }

    fun disconnect() {
        viewModelScope.launch { disconnectDrive() }
    }

    fun setAuto(enabled: Boolean) {
        viewModelScope.launch { setAutoBackup(enabled) }
    }

    fun setWifi(wifiOnly: Boolean) {
        viewModelScope.launch { setWifiOnly(wifiOnly) }
    }

    fun backUpNow() {
        requestBackupNow()
        _ui.update { it.copy(notice = BackupNotice.Queued) }
    }

    fun openRestore() {
        _ui.update { it.copy(restore = RestoreStep.Loading) }
        viewModelScope.launch {
            when (val result = listBackups()) {
                is CloudResult.Success -> _ui.update { it.copy(restore = RestoreStep.Pick(result.data)) }
                CloudResult.AuthRequired -> _ui.update { it.copy(restore = RestoreStep.Hidden, notice = BackupNotice.AuthRequired) }
                is CloudResult.Failure -> _ui.update { it.copy(restore = RestoreStep.Hidden, notice = BackupNotice.Failed(result.message)) }
            }
        }
    }

    fun choose(backup: RemoteBackup) = _ui.update { it.copy(restore = RestoreStep.Confirm(backup)) }

    fun closeRestore() = _ui.update { it.copy(restore = RestoreStep.Hidden) }

    /** After the person confirmed "This replaces everything on this phone". A successful restore restarts the app. */
    fun confirmRestore(backup: RemoteBackup) {
        _ui.update { it.copy(restore = RestoreStep.Restoring) }
        viewModelScope.launch {
            val notice = when (val outcome = restoreBackup(backup)) {
                RestoreOutcome.Restarting -> null
                RestoreOutcome.NotConnected, RestoreOutcome.AuthRequired -> BackupNotice.AuthRequired
                is RestoreOutcome.NewerThanApp -> BackupNotice.NewerApp
                is RestoreOutcome.Failed -> BackupNotice.Failed(outcome.message)
            }
            _ui.update { it.copy(restore = RestoreStep.Hidden, notice = notice) }
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
