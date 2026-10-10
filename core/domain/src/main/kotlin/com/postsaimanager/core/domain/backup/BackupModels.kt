package com.postsaimanager.core.domain.backup

import com.postsaimanager.core.common.backup.ConsentRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The first entry of every backup archive (`manifest.json`): what the archive holds and which app wrote it, so a restore can refuse
 * a backup it cannot read before it touches anything on the phone.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val appVersionName: String,
    val appVersionCode: Long,
    /** The Room schema version of the archived database. A newer one than the app's is refused; an older one is migrated on open. */
    val dbSchemaVersion: Int,
    val createdAt: Long,
    val deviceName: String,
    val documentCount: Int,
    val fileCount: Int,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "manifest.json"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Null when [text] is not a manifest. */
        fun fromJson(text: String): BackupManifest? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Whether this app may restore an archive, decided from its manifest alone. */
sealed interface BackupCompatibility {
    data object Compatible : BackupCompatibility

    /** The backup was written by a newer app: its database may hold tables this app does not know. */
    data class NewerThanApp(val backupSchema: Int, val appSchema: Int) : BackupCompatibility

    /** The archive layout is from a future (or unknown) format. */
    data class UnsupportedFormat(val formatVersion: Int) : BackupCompatibility

    companion object {
        fun check(manifest: BackupManifest, appSchemaVersion: Int): BackupCompatibility = when {
            manifest.formatVersion !in 1..BackupManifest.FORMAT_VERSION -> UnsupportedFormat(manifest.formatVersion)
            manifest.dbSchemaVersion > appSchemaVersion -> NewerThanApp(manifest.dbSchemaVersion, appSchemaVersion)
            else -> Compatible
        }
    }
}

/** One backup in the user's Drive, as the restore list shows it. Schema and device are null for a file made by something else. */
data class RemoteBackup(
    val id: String,
    val name: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val deviceName: String?,
    val dbSchemaVersion: Int?,
    val appVersionName: String?,
)

/** Which Google account the app is connected to, if any. The e-mail is for display only; no token is stored. */
sealed interface DriveAccount {
    data object Disconnected : DriveAccount
    data class Connected(val email: String?) : DriveAccount
}

/** The answer of asking Google for Drive access. */
sealed interface DriveAuthorization {
    data class Granted(val email: String?) : DriveAuthorization

    /** The person has to agree first (the first time, or after access was revoked). */
    data class NeedsConsent(val request: ConsentRequest) : DriveAuthorization

    data class Failed(val message: String) : DriveAuthorization
}

/** What a call to Drive returned. */
sealed interface CloudResult<out T> {
    data class Success<T>(val data: T) : CloudResult<T>

    /** Not connected, or Google wants the consent again: Settings has to connect the account. */
    data object AuthRequired : CloudResult<Nothing>

    data class Failure(val message: String) : CloudResult<Nothing>
}

/** The user's two backup switches. */
data class BackupSettings(
    val autoBackup: Boolean = false,
    /** Automatic backups wait for an unmetered network (Wi-Fi). On by default: a backup can be hundreds of MB. */
    val wifiOnly: Boolean = true,
)

/** The last finished backup, for the Settings line. */
data class BackupStatus(
    val lastSuccessAt: Long? = null,
    val lastSizeBytes: Long? = null,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
)

enum class BackupStage { PACKING, UPLOADING, DOWNLOADING, RESTORING, CLEANING }

/** A backup or restore in progress; [fraction] is null while the length is not known. */
data class BackupProgress(val stage: BackupStage, val fraction: Float? = null)

/** An archive that was just made. */
data class BackupArchive(val file: java.io.File, val manifest: BackupManifest)

/** How a backup run ended. */
sealed interface BackupOutcome {
    data class Done(val backup: RemoteBackup) : BackupOutcome

    /** A reading or a chat was running for too long to wait for. */
    data object Busy : BackupOutcome

    data object NotConnected : BackupOutcome
    data object AuthRequired : BackupOutcome
    data class Failed(val message: String) : BackupOutcome
}

/** How a restore ended (when it returns: a successful one restarts the app). */
sealed interface RestoreOutcome {
    data object Restarting : RestoreOutcome
    data object NotConnected : RestoreOutcome
    data object AuthRequired : RestoreOutcome
    data class NewerThanApp(val backupSchema: Int, val appSchema: Int) : RestoreOutcome
    data class Failed(val message: String) : RestoreOutcome
}

/** How [BackupArchiver.restore] ended. */
sealed interface ArchiveRestore {
    data object Restored : ArchiveRestore

    /** Nothing was changed; the app keeps running. */
    data class Refused(val message: String) : ArchiveRestore

    /** The database was closed before the swap failed: the app has to restart even so. */
    data class FailedAfterClose(val message: String) : ArchiveRestore
}
