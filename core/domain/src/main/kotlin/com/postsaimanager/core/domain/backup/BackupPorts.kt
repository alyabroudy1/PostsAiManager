package com.postsaimanager.core.domain.backup

import kotlinx.coroutines.flow.Flow
import java.io.File

/** The backup copies in the person's own Google Drive (its hidden app folder). One implementation: the Drive v3 REST client. */
interface CloudBackupStore {

    /** Uploads [file] as a new backup described by [manifest]. [onProgress] gets (bytes sent, total bytes). */
    suspend fun upload(file: File, manifest: BackupManifest, onProgress: (Long, Long) -> Unit): CloudResult<RemoteBackup>

    suspend fun list(): CloudResult<List<RemoteBackup>>

    /** Streams [backup] into [target]. [onProgress] gets (bytes received, total bytes). */
    suspend fun download(backup: RemoteBackup, target: File, onProgress: (Long, Long) -> Unit): CloudResult<Unit>

    suspend fun delete(backup: RemoteBackup): CloudResult<Unit>
}

/** The link to the Google account: what the person agreed to, and the e-mail to show. No token is kept; Google hands out a fresh one on demand. */
interface DriveConnection {

    val account: Flow<DriveAccount>

    suspend fun isConnected(): Boolean

    /** Asks Google for Drive access: granted, or a consent screen to show, or a failure. A granted answer marks the account as connected. */
    suspend fun authorize(): DriveAuthorization

    /** Revokes the app's access and forgets the account. Backups already in Drive stay there. */
    suspend fun disconnect()
}

/** Makes and restores the archive of this phone's own data: the database and the document and chat files, never the AI models or caches. */
interface BackupArchiver {

    /** The Room schema version of the database that is open now. */
    val currentSchemaVersion: Int

    /** Writes a new archive into the workspace. The caller deletes it with [BackupWorkspace.discard]. */
    suspend fun create(): BackupArchive

    /** Reads and checks the manifest of a downloaded [archive]; null when it is not one of ours. */
    suspend fun readManifest(archive: File): BackupManifest?

    /** Replaces everything on this phone with the archive: stages it first, then swaps it in. Restarting the app is the caller's job. */
    suspend fun restore(archive: File): ArchiveRestore
}

/** Where archives are written and downloaded to (the app's cache, so the system may reclaim it). */
interface BackupWorkspace {
    fun newFile(name: String): File
    fun discard(file: File)
}

/** The two switches. */
interface BackupPreferences {
    val settings: Flow<BackupSettings>
    suspend fun current(): BackupSettings
    suspend fun setAutoBackup(enabled: Boolean)
    suspend fun setWifiOnly(wifiOnly: Boolean)
}

/** The last result and the run in progress, for Settings. */
interface BackupStatusStore {
    val status: Flow<BackupStatus>
    val progress: Flow<BackupProgress?>
    fun setProgress(progress: BackupProgress?)
    suspend fun recordSuccess(at: Long, sizeBytes: Long)
    suspend fun recordFailure(at: Long, message: String)
}

/** The daily job and the "Back up now" job. */
interface BackupScheduler {
    fun schedule(wifiOnly: Boolean)
    fun cancel()
    fun runNow()
}

/** Restarts the app process cleanly (after a restore, when every open file and cache is stale). */
interface AppRestarter {
    fun restart()
}

/** Whether the phone is quiet enough for a background backup: no reading and no live or parked chat. */
interface BackupGate {
    /** Waits until quiet for at most [maxWaitMs]; false when it is still busy. */
    suspend fun awaitQuiet(maxWaitMs: Long): Boolean
}

/** How many backups Drive keeps. */
object BackupRetention {
    const val KEEP = 3

    /** The backups to delete: everything past the newest [keep]. [protectedId] (the one just made) is never listed. */
    fun toDelete(all: List<RemoteBackup>, protectedId: String? = null, keep: Int = KEEP): List<RemoteBackup> =
        all.sortedByDescending { it.createdAt }.drop(keep).filter { it.id != protectedId }
}
