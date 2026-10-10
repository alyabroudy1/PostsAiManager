package com.postsaimanager.core.data.backup

import android.content.Context
import android.os.Build
import com.jakewharton.processphoenix.ProcessPhoenix
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.backup.AppRestarter
import com.postsaimanager.core.domain.backup.BackupArchiver
import com.postsaimanager.core.domain.backup.BackupGate
import com.postsaimanager.core.domain.backup.BackupPreferences
import com.postsaimanager.core.domain.backup.BackupScheduler
import com.postsaimanager.core.domain.backup.BackupStatusStore
import com.postsaimanager.core.domain.backup.BackupWorkspace
import com.postsaimanager.core.domain.backup.CloudBackupStore
import com.postsaimanager.core.domain.backup.DriveConnection
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.model.ProcessingState
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The Room database for the archiver: the checkpoint, the schema version that is open, and the close before a restore swaps the file. */
internal class RoomBackupDatabase(private val database: Lazy<PamDatabase>) : BackupDatabase {

    override fun checkpoint() {
        // The result row (busy, log, checkpointed) is read only to run the statement; FULL waits for writers instead of skipping frames.
        database.get().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    override val schemaVersion: Int get() = database.get().openHelper.readableDatabase.version

    override fun close() = database.get().close()
}

/** A background backup waits while a reading runs or a chat is live (or parked): the model and the disk belong to them. */
class QuietBackupGate @Inject constructor(
    private val processor: DocumentProcessor,
    private val chat: ChatActivityGate,
) : BackupGate {

    override suspend fun awaitQuiet(maxWaitMs: Long): Boolean {
        var waited = 0L
        while (!isQuiet()) {
            if (waited >= maxWaitMs) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return true
    }

    private suspend fun isQuiet(): Boolean =
        !chat.isChatActive() && processor.processingState.first() !is ProcessingState.Running && processor.enrichingDocuments.first().isEmpty()

    private companion object {
        const val POLL_MS = 5_000L
    }
}

/** A clean restart of the process (the old one, with its closed database and stale caches, is killed). */
class ProcessPhoenixAppRestarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppRestarter {
    override fun restart() = ProcessPhoenix.triggerRebirth(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupBindingsModule {

    @Binds
    abstract fun bindDriveConnection(impl: GoogleDriveConnection): DriveConnection

    @Binds
    abstract fun bindPreferences(impl: SharedPreferencesBackupState): BackupPreferences

    @Binds
    abstract fun bindStatus(impl: SharedPreferencesBackupState): BackupStatusStore

    @Binds
    abstract fun bindWorkspace(impl: CacheBackupWorkspace): BackupWorkspace

    @Binds
    abstract fun bindScheduler(impl: WorkManagerBackupScheduler): BackupScheduler

    @Binds
    abstract fun bindGate(impl: QuietBackupGate): BackupGate

    @Binds
    abstract fun bindRestarter(impl: ProcessPhoenixAppRestarter): AppRestarter
}

@Module
@InstallIn(SingletonComponent::class)
object BackupProvidesModule {

    @Provides
    @Singleton
    fun provideCloudBackupStore(connection: GoogleDriveConnection): CloudBackupStore = DriveRestCloudBackupStore(connection)

    @Provides
    @Singleton
    fun provideBackupArchiver(
        @ApplicationContext context: Context,
        database: Lazy<PamDatabase>,
        workspace: BackupWorkspace,
    ): BackupArchiver = ZipBackupArchiver(
        layout = BackupLayout(
            databaseFile = context.getDatabasePath(PamDatabase.DATABASE_NAME),
            documentsDir = File(context.filesDir, "documents"),
            chatAttachmentsDir = File(context.filesDir, "chat-attachments"),
            stagingDir = File(context.filesDir, "restore-staging"),
        ),
        database = RoomBackupDatabase(database),
        appInfo = {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            BackupAppInfo(
                versionName = info?.versionName ?: "?",
                versionCode = info?.longVersionCode ?: 0L,
                deviceName = Build.MODEL.orEmpty().ifBlank { "Android" },
            )
        },
        workspace = workspace,
    )
}
