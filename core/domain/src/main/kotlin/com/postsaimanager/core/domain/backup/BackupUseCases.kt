package com.postsaimanager.core.domain.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import javax.inject.Inject

/**
 * One backup run: packs this phone's data, uploads it to Drive, then deletes backups older than the newest [BackupRetention.KEEP].
 * The daily job passes [waitForQuiet] so it never competes with a reading or a chat; "Back up now" does not wait, the person asked.
 * The result (and any failure) is recorded for the Settings line, whatever started the run.
 */
class BackUpNowUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val archiver: BackupArchiver,
    private val workspace: BackupWorkspace,
    private val store: CloudBackupStore,
    private val gate: BackupGate,
    private val status: BackupStatusStore,
    private val clock: Clock,
) {

    suspend operator fun invoke(waitForQuiet: Boolean = false): BackupOutcome {
        if (!connection.isConnected()) return BackupOutcome.NotConnected
        if (waitForQuiet && !gate.awaitQuiet(QUIET_WAIT_MS)) return BackupOutcome.Busy
        var archiveFile: java.io.File? = null
        try {
            status.setProgress(BackupProgress(BackupStage.PACKING))
            val archive = archiver.create()
            archiveFile = archive.file
            status.setProgress(BackupProgress(BackupStage.UPLOADING, 0f))
            val uploaded = store.upload(archive.file, archive.manifest) { sent, total ->
                status.setProgress(BackupProgress(BackupStage.UPLOADING, if (total > 0) sent.toFloat() / total else null))
            }
            when (uploaded) {
                CloudResult.AuthRequired -> return BackupOutcome.AuthRequired
                is CloudResult.Failure -> return fail(uploaded.message)
                is CloudResult.Success -> {
                    status.setProgress(BackupProgress(BackupStage.CLEANING))
                    trimOldBackups(uploaded.data)
                    status.recordSuccess(clock.millis(), uploaded.data.sizeBytes)
                    return BackupOutcome.Done(uploaded.data)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        } finally {
            archiveFile?.let(workspace::discard)
            status.setProgress(null)
        }
    }

    /** Keeping the old copies is not worth failing a finished backup over, so a cleanup problem is left for the next run. */
    private suspend fun trimOldBackups(justMade: RemoteBackup) {
        val all = (store.list() as? CloudResult.Success)?.data ?: return
        BackupRetention.toDelete(all, protectedId = justMade.id).forEach { store.delete(it) }
    }

    private suspend fun fail(message: String): BackupOutcome {
        status.recordFailure(clock.millis(), message)
        return BackupOutcome.Failed(message)
    }

    companion object {
        /** How long the daily job waits for a reading or chat to end before it asks to be run again. */
        const val QUIET_WAIT_MS = 5 * 60_000L
    }
}

/** The backups in Drive, newest first. */
class ListBackupsUseCase @Inject constructor(private val store: CloudBackupStore) {
    suspend operator fun invoke(): CloudResult<List<RemoteBackup>> = when (val result = store.list()) {
        is CloudResult.Success -> CloudResult.Success(result.data.sortedByDescending { it.createdAt })
        else -> result
    }
}

/**
 * Restores [backup] over everything on this phone: refuses a newer app's backup, downloads it, checks its manifest, swaps the files in
 * and restarts the app. Nothing on the phone changes until the archive has been downloaded and verified.
 */
class RestoreBackupUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val archiver: BackupArchiver,
    private val workspace: BackupWorkspace,
    private val store: CloudBackupStore,
    private val status: BackupStatusStore,
    private val restarter: AppRestarter,
) {

    suspend operator fun invoke(backup: RemoteBackup): RestoreOutcome {
        if (!connection.isConnected()) return RestoreOutcome.NotConnected
        val appSchema = archiver.currentSchemaVersion
        backup.dbSchemaVersion?.let { if (it > appSchema) return RestoreOutcome.NewerThanApp(it, appSchema) }
        val target = workspace.newFile("restore-${backup.id}.zip")
        try {
            status.setProgress(BackupProgress(BackupStage.DOWNLOADING, 0f))
            when (val got = store.download(backup, target) { received, total ->
                status.setProgress(BackupProgress(BackupStage.DOWNLOADING, if (total > 0) received.toFloat() / total else null))
            }) {
                CloudResult.AuthRequired -> return RestoreOutcome.AuthRequired
                is CloudResult.Failure -> return RestoreOutcome.Failed(got.message)
                is CloudResult.Success -> Unit
            }
            val manifest = archiver.readManifest(target) ?: return RestoreOutcome.Failed("This file is not a backup of this app.")
            when (val check = BackupCompatibility.check(manifest, appSchema)) {
                BackupCompatibility.Compatible -> Unit
                is BackupCompatibility.NewerThanApp -> return RestoreOutcome.NewerThanApp(check.backupSchema, check.appSchema)
                is BackupCompatibility.UnsupportedFormat -> return RestoreOutcome.Failed("Unsupported backup format ${check.formatVersion}.")
            }
            status.setProgress(BackupProgress(BackupStage.RESTORING))
            when (val restored = archiver.restore(target)) {
                ArchiveRestore.Restored -> {
                    workspace.discard(target)
                    restarter.restart()
                    return RestoreOutcome.Restarting
                }
                is ArchiveRestore.Refused -> return RestoreOutcome.Failed(restored.message)
                is ArchiveRestore.FailedAfterClose -> {
                    workspace.discard(target)
                    restarter.restart()
                    return RestoreOutcome.Failed(restored.message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RestoreOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            workspace.discard(target)
            status.setProgress(null)
        }
    }
}

/** What Settings shows: the account, the switches, the last result and the run in progress. */
data class BackupOverview(
    val account: DriveAccount,
    val settings: BackupSettings,
    val status: BackupStatus,
    val progress: BackupProgress?,
)

class GetBackupStatusUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val preferences: BackupPreferences,
    private val statusStore: BackupStatusStore,
) {
    operator fun invoke(): Flow<BackupOverview> = combine(
        connection.account,
        preferences.settings,
        statusStore.status,
        statusStore.progress,
    ) { account, settings, status, progress -> BackupOverview(account, settings, status, progress) }
}

/**
 * Brings the daily job in line with the switches: scheduled while the account is connected and automatic backup is on, cancelled
 * otherwise. Called after every change of a switch, after connecting and after disconnecting.
 */
class ScheduleAutoBackupUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val preferences: BackupPreferences,
    private val scheduler: BackupScheduler,
) {
    suspend operator fun invoke() {
        val settings = preferences.current()
        if (connection.isConnected() && settings.autoBackup) scheduler.schedule(settings.wifiOnly) else scheduler.cancel()
    }
}

/** "Back up now": queues the run as a background job, so it goes on when the person leaves the screen. */
class RequestBackupNowUseCase @Inject constructor(private val scheduler: BackupScheduler) {
    operator fun invoke() = scheduler.runNow()
}

/**
 * Connects the Google account. The first time it also turns the daily backup on (the person asked for a backup by connecting).
 * A [DriveAuthorization.NeedsConsent] answer is shown by the screen, which calls this again when the consent screen returns.
 */
class ConnectDriveUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val preferences: BackupPreferences,
    private val schedule: ScheduleAutoBackupUseCase,
) {
    suspend operator fun invoke(): DriveAuthorization {
        val wasConnected = connection.isConnected()
        val result = connection.authorize()
        if (result is DriveAuthorization.Granted) {
            if (!wasConnected) preferences.setAutoBackup(true)
            schedule()
        }
        return result
    }
}

class DisconnectDriveUseCase @Inject constructor(
    private val connection: DriveConnection,
    private val preferences: BackupPreferences,
    private val scheduler: BackupScheduler,
) {
    suspend operator fun invoke() {
        scheduler.cancel()
        preferences.setAutoBackup(false)
        connection.disconnect()
    }
}

class SetAutoBackupUseCase @Inject constructor(
    private val preferences: BackupPreferences,
    private val schedule: ScheduleAutoBackupUseCase,
) {
    suspend operator fun invoke(enabled: Boolean) {
        preferences.setAutoBackup(enabled)
        schedule()
    }
}

class SetBackupWifiOnlyUseCase @Inject constructor(
    private val preferences: BackupPreferences,
    private val schedule: ScheduleAutoBackupUseCase,
) {
    suspend operator fun invoke(wifiOnly: Boolean) {
        preferences.setWifiOnly(wifiOnly)
        schedule()
    }
}
