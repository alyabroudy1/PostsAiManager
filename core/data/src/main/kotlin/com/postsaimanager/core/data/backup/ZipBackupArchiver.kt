package com.postsaimanager.core.data.backup

import com.postsaimanager.core.domain.backup.ArchiveRestore
import com.postsaimanager.core.domain.backup.BackupArchive
import com.postsaimanager.core.domain.backup.BackupArchiver
import com.postsaimanager.core.domain.backup.BackupCompatibility
import com.postsaimanager.core.domain.backup.BackupManifest
import com.postsaimanager.core.domain.backup.BackupWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Where the app's own data lives on this phone, and the staging folder a restore unpacks into (on the same volume, so the swap is a rename). */
class BackupLayout(
    val databaseFile: File,
    val documentsDir: File,
    val chatAttachmentsDir: File,
    val stagingDir: File,
)

/** The open Room database as the archiver needs it. */
interface BackupDatabase {
    /** Folds the write-ahead log into the main file, so copying that one file copies everything committed. */
    fun checkpoint()

    val schemaVersion: Int

    fun close()
}

class BackupAppInfo(val versionName: String, val versionCode: Long, val deviceName: String)

/**
 * The backup archive: one zip with `manifest.json` first, then `database/<name>` (the Room file after a WAL checkpoint) and
 * `files/documents/...` and `files/chat-attachments/...` (page images, original PDFs, chat pictures). The AI models, the search
 * model, the import queue, caches and preferences are not in it: the models are downloaded again, and preferences (app lock, accounts)
 * belong to the phone. Everything is streamed through a small buffer, so a big archive never sits in memory.
 *
 * Consistency: the daily job runs only while no reading or chat is active ([com.postsaimanager.core.domain.backup.BackupGate]), so
 * nothing writes the database while it is copied; a person's own edit in that moment would at worst be missed by this one backup.
 */
class ZipBackupArchiver(
    private val layout: BackupLayout,
    private val database: BackupDatabase,
    private val appInfo: () -> BackupAppInfo,
    private val workspace: BackupWorkspace,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupArchiver {

    override val currentSchemaVersion: Int get() = database.schemaVersion

    override suspend fun create(): BackupArchive = withContext(Dispatchers.IO) {
        database.checkpoint()
        check(layout.databaseFile.isFile) { "The database file is missing." }
        val documentFiles = filesIn(layout.documentsDir)
        val chatFiles = filesIn(layout.chatAttachmentsDir)
        val info = appInfo()
        val createdAt = clock()
        val manifest = BackupManifest(
            appVersionName = info.versionName,
            appVersionCode = info.versionCode,
            dbSchemaVersion = database.schemaVersion,
            createdAt = createdAt,
            deviceName = info.deviceName,
            documentCount = layout.documentsDir.listFiles { f -> f.isDirectory }?.size ?: 0,
            fileCount = documentFiles.size + chatFiles.size,
        )
        val target = workspace.newFile("pam-backup-$createdAt.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target), BUFFER)).use { zip ->
                // Photos and PDFs are compressed already; the fast level costs little time and still shrinks the database.
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
                zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                addFile(zip, DATABASE_ENTRY, layout.databaseFile)
                documentFiles.forEach { addFile(zip, DOCUMENTS_PREFIX + relative(layout.documentsDir, it), it) }
                chatFiles.forEach { addFile(zip, CHAT_PREFIX + relative(layout.chatAttachmentsDir, it), it) }
            }
        } catch (e: Throwable) {
            workspace.discard(target)
            throw e
        }
        BackupArchive(target, manifest)
    }

    override suspend fun readManifest(archive: File): BackupManifest? = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(archive).use { zip ->
                if (zip.getEntry(DATABASE_ENTRY) == null) return@use null
                val entry = zip.getEntry(BackupManifest.FILE_NAME) ?: return@use null
                BackupManifest.fromJson(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
            }
        }.getOrNull()
    }

    override suspend fun restore(archive: File): ArchiveRestore = withContext(Dispatchers.IO) {
        val manifest = readManifest(archive) ?: return@withContext ArchiveRestore.Refused("This file is not a backup of this app.")
        when (val check = BackupCompatibility.check(manifest, database.schemaVersion)) {
            BackupCompatibility.Compatible -> Unit
            is BackupCompatibility.NewerThanApp -> return@withContext ArchiveRestore.Refused("The backup is from a newer version of the app.")
            is BackupCompatibility.UnsupportedFormat -> return@withContext ArchiveRestore.Refused("Unsupported backup format ${check.formatVersion}.")
        }
        val staged = try {
            stage(archive)
        } catch (e: Exception) {
            layout.stagingDir.deleteRecursively()
            return@withContext ArchiveRestore.Refused(e.message ?: "The backup could not be unpacked.")
        }
        // Past this line the database is closed: whatever happens next, the app must restart.
        database.close()
        try {
            swapIn(staged)
            ArchiveRestore.Restored
        } catch (e: Exception) {
            ArchiveRestore.FailedAfterClose(e.message ?: "The backup could not be put in place.")
        } finally {
            layout.stagingDir.deleteRecursively()
        }
    }

    private class Staged(val database: File, val documents: File, val chatAttachments: File)

    /** Unpacks into the staging folder; nothing outside it is written, and an entry that would leave it fails the restore. */
    private fun stage(archive: File): Staged {
        val root = layout.stagingDir
        root.deleteRecursively()
        val staged = Staged(File(root, "database"), File(root, "documents"), File(root, "chat-attachments"))
        val rootPath = root.canonicalFile.toPath()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val destination = when {
                    entry.name == DATABASE_ENTRY -> staged.database
                    entry.name.startsWith(DOCUMENTS_PREFIX) -> File(staged.documents, entry.name.removePrefix(DOCUMENTS_PREFIX))
                    entry.name.startsWith(CHAT_PREFIX) -> File(staged.chatAttachments, entry.name.removePrefix(CHAT_PREFIX))
                    else -> continue
                }
                require(destination.canonicalFile.toPath().startsWith(rootPath)) { "The backup holds an unsafe file name." }
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(destination).use { input.copyTo(it, BUFFER) } }
            }
        }
        require(staged.database.isFile && hasSqliteHeader(staged.database)) { "The backup's database is damaged." }
        return staged
    }

    /** Puts the staged data in place with renames: each current item is set aside first and put back if a later step fails. */
    private fun swapIn(staged: Staged) {
        val done = mutableListOf<Swap>()
        try {
            done += swap(layout.documentsDir, staged.documents)
            done += swap(layout.chatAttachmentsDir, staged.chatAttachments)
            listOf("-wal", "-shm", "-journal").forEach { File(layout.databaseFile.path + it).delete() }
            done += swap(layout.databaseFile, staged.database)
        } catch (e: Exception) {
            done.asReversed().forEach { it.undo() }
            throw e
        }
        done.forEach { it.old.deleteRecursively() }
    }

    private class Swap(val target: File, val old: File) {
        fun undo() {
            if (target.exists()) target.deleteRecursively()
            if (old.exists()) move(old, target)
        }
    }

    private fun swap(target: File, staged: File): Swap {
        val old = File(target.path + OLD_SUFFIX)
        old.deleteRecursively()
        val swap = Swap(target, old)
        if (target.exists()) move(target, old)
        try {
            target.parentFile?.mkdirs()
            if (staged.exists()) move(staged, target)
        } catch (e: Exception) {
            swap.undo()
            throw e
        }
        return swap
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip, BUFFER) }
        zip.closeEntry()
    }

    private fun filesIn(dir: File): List<File> = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else emptyList()

    private fun relative(root: File, file: File): String = file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun hasSqliteHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input -> ByteArray(SQLITE_MAGIC.length).also { input.read(it) }.toString(Charsets.ISO_8859_1) == SQLITE_MAGIC }
    }.getOrDefault(false)

    companion object {
        const val DATABASE_ENTRY = "database/pam_database"
        const val DOCUMENTS_PREFIX = "files/documents/"
        const val CHAT_PREFIX = "files/chat-attachments/"
        private const val OLD_SUFFIX = ".pre-restore"
        private const val BUFFER = 64 * 1024
        private const val SQLITE_MAGIC = "SQLite format 3\u0000"

        private fun move(from: File, to: File) {
            // Same volume, so a rename; the atomic form fails loudly where the plain one could silently copy.
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
