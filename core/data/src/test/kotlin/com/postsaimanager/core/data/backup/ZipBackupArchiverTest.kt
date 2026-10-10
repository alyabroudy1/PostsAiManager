package com.postsaimanager.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.backup.ArchiveRestore
import com.postsaimanager.core.domain.backup.BackupManifest
import com.postsaimanager.core.domain.backup.BackupWorkspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The archive round trip on temp folders: what goes into a backup, what comes back, and what a restore refuses. */
class ZipBackupArchiverTest {

    @TempDir
    lateinit var root: File

    private class FakeDatabase(var schema: Int = 29) : BackupDatabase {
        var checkpoints = 0
        var closed = false
        override fun checkpoint() { checkpoints++ }
        override val schemaVersion get() = schema
        override fun close() { closed = true }
    }

    private class TempWorkspace(private val dir: File) : BackupWorkspace {
        override fun newFile(name: String) = File(dir.apply { mkdirs() }, name)
        override fun discard(file: File) { file.delete() }
    }

    private class Phone(val home: File, val database: FakeDatabase = FakeDatabase()) {
        val layout = BackupLayout(
            databaseFile = File(home, "databases/pam_database"),
            documentsDir = File(home, "files/documents"),
            chatAttachmentsDir = File(home, "files/chat-attachments"),
            stagingDir = File(home, "files/restore-staging"),
        )
        val workspace = TempWorkspace(File(home, "cache/backup"))
        val archiver = ZipBackupArchiver(layout, database, { BackupAppInfo("1.0.0", 1, "Pixel") }, workspace, clock = { 1_234L })

        fun write(file: File, text: String) { file.parentFile.mkdirs(); file.writeText(text) }
        fun withData() = apply {
            layout.databaseFile.parentFile.mkdirs()
            layout.databaseFile.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + "rows".toByteArray())
            write(File(layout.documentsDir, "doc1/page-0.jpg"), "image-1")
            write(File(layout.documentsDir, "doc1/original.pdf"), "pdf-1")
            write(File(layout.documentsDir, "doc2/page-0.jpg"), "image-2")
            write(File(layout.chatAttachmentsDir, "conv/a.png"), "chat-a")
            // Things that must stay out of the archive.
            write(File(home, "files/models/model.gguf"), "huge model")
            write(File(home, "cache/other.tmp"), "cache")
        }
    }

    private fun phone(name: String) = Phone(File(root, name).apply { mkdirs() })

    @Test
    fun `an archive holds the manifest, the database and the document and chat files, and no model`() = runTest {
        val source = phone("source").withData()
        val archive = source.archiver.create()

        assertThat(source.database.checkpoints).isEqualTo(1)
        assertThat(archive.manifest.documentCount).isEqualTo(2)
        assertThat(archive.manifest.fileCount).isEqualTo(4)
        assertThat(archive.manifest.dbSchemaVersion).isEqualTo(29)
        assertThat(archive.manifest.deviceName).isEqualTo("Pixel")
        val names = java.util.zip.ZipFile(archive.file).use { zip -> zip.entries().toList().map { it.name } }
        assertThat(names.first()).isEqualTo(BackupManifest.FILE_NAME)
        assertThat(names).containsAtLeast(
            "database/pam_database",
            "files/documents/doc1/page-0.jpg",
            "files/documents/doc1/original.pdf",
            "files/documents/doc2/page-0.jpg",
            "files/chat-attachments/conv/a.png",
        )
        assertThat(names.none { it.contains("model") || it.contains("cache") }).isTrue()
        assertThat(source.archiver.readManifest(archive.file)).isEqualTo(archive.manifest)
    }

    @Test
    fun `restoring into another phone brings back every file and replaces what was there`() = runTest {
        val source = phone("source").withData()
        val archive = source.archiver.create()

        val target = phone("target")
        target.layout.databaseFile.parentFile.mkdirs()
        target.layout.databaseFile.writeText("old database")
        File(target.layout.databaseFile.path + "-wal").writeText("old wal")
        target.write(File(target.layout.documentsDir, "stale/page-0.jpg"), "stale")
        target.write(File(target.layout.chatAttachmentsDir, "old/x.png"), "old chat")

        val result = target.archiver.restore(archive.file)

        assertThat(result).isEqualTo(ArchiveRestore.Restored)
        assertThat(target.database.closed).isTrue()
        assertThat(target.layout.databaseFile.readBytes()).isEqualTo(source.layout.databaseFile.readBytes())
        assertThat(File(target.layout.databaseFile.path + "-wal").exists()).isFalse()
        assertThat(File(target.layout.documentsDir, "doc1/page-0.jpg").readText()).isEqualTo("image-1")
        assertThat(File(target.layout.documentsDir, "doc1/original.pdf").readText()).isEqualTo("pdf-1")
        assertThat(File(target.layout.documentsDir, "doc2/page-0.jpg").readText()).isEqualTo("image-2")
        assertThat(File(target.layout.chatAttachmentsDir, "conv/a.png").readText()).isEqualTo("chat-a")
        assertThat(File(target.layout.documentsDir, "stale").exists()).isFalse()
        assertThat(File(target.layout.chatAttachmentsDir, "old").exists()).isFalse()
        assertThat(target.layout.stagingDir.exists()).isFalse()
        assertThat(File(target.layout.documentsDir.path + ".pre-restore").exists()).isFalse()
    }

    @Test
    fun `a backup with no chat files empties the chat folder`() = runTest {
        val source = phone("source").withData()
        source.layout.chatAttachmentsDir.deleteRecursively()
        val archive = source.archiver.create()
        val target = phone("target").withData()
        assertThat(target.archiver.restore(archive.file)).isEqualTo(ArchiveRestore.Restored)
        assertThat(target.layout.chatAttachmentsDir.exists()).isFalse()
    }

    @Test
    fun `a backup from a newer schema is refused and nothing is touched or closed`() = runTest {
        val source = phone("source").withData()
        source.database.schema = 31
        val archive = source.archiver.create()

        val target = phone("target").withData()
        target.database.schema = 29
        val result = target.archiver.restore(archive.file)

        assertThat(result).isInstanceOf(ArchiveRestore.Refused::class.java)
        assertThat(target.database.closed).isFalse()
        assertThat(File(target.layout.documentsDir, "doc1/page-0.jpg").readText()).isEqualTo("image-1")
    }

    @Test
    fun `an older schema restores, to be migrated by Room when it opens`() = runTest {
        val source = phone("source").withData()
        source.database.schema = 20
        val archive = source.archiver.create()
        val target = phone("target")
        target.database.schema = 29
        assertThat(target.archiver.restore(archive.file)).isEqualTo(ArchiveRestore.Restored)
    }

    @Test
    fun `a zip that is not a backup is refused`() = runTest {
        val target = phone("target").withData()
        val junk = File(root, "junk.zip")
        ZipOutputStream(junk.outputStream()).use { it.putNextEntry(ZipEntry("hello.txt")); it.write(1); it.closeEntry() }
        assertThat(target.archiver.readManifest(junk)).isNull()
        assertThat(target.archiver.restore(junk)).isInstanceOf(ArchiveRestore.Refused::class.java)
        val notZip = File(root, "text.zip").apply { writeText("nope") }
        assertThat(target.archiver.readManifest(notZip)).isNull()
        assertThat(target.database.closed).isFalse()
    }

    @Test
    fun `an entry that climbs out of the staging folder is refused before anything changes`() = runTest {
        val target = phone("target").withData()
        val manifest = BackupManifest(appVersionName = "1", appVersionCode = 1, dbSchemaVersion = 29, createdAt = 1, deviceName = "d", documentCount = 0, fileCount = 1)
        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BackupManifest.FILE_NAME)); zip.write(manifest.toJson().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(ZipBackupArchiver.DATABASE_ENTRY)); zip.write("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(ZipBackupArchiver.DOCUMENTS_PREFIX + "../../escaped.txt")); zip.write(1); zip.closeEntry()
        }
        val result = target.archiver.restore(evil)
        assertThat(result).isInstanceOf(ArchiveRestore.Refused::class.java)
        assertThat(target.database.closed).isFalse()
        assertThat(File(target.home, "files/escaped.txt").exists()).isFalse()
        assertThat(File(target.layout.documentsDir, "doc1/page-0.jpg").readText()).isEqualTo("image-1")
    }

    @Test
    fun `a damaged database in the archive is refused before the database is closed`() = runTest {
        val source = phone("source").withData()
        source.layout.databaseFile.writeText("this is not sqlite")
        val archive = source.archiver.create()
        val target = phone("target").withData()
        assertThat(target.archiver.restore(archive.file)).isInstanceOf(ArchiveRestore.Refused::class.java)
        assertThat(target.database.closed).isFalse()
    }
}
