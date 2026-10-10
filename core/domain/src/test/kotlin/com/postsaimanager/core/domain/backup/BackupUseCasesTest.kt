package com.postsaimanager.core.domain.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The backup and restore use cases against fakes of every port: order of steps, retention, status, gate and the version refusal. */
class BackupUseCasesTest {

    private val clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC)

    private fun remote(id: String, createdAt: Long, schema: Int? = 29) =
        RemoteBackup(id, "$id.zip", createdAt, sizeBytes = 100, deviceName = "Pixel", dbSchemaVersion = schema, appVersionName = "1.0.0")

    private class FakeConnection(var connected: Boolean = true) : DriveConnection {
        override val account: Flow<DriveAccount> = MutableStateFlow(DriveAccount.Connected("a@b.c"))
        override suspend fun isConnected() = connected
        override suspend fun authorize(): DriveAuthorization = DriveAuthorization.Granted("a@b.c").also { connected = true }
        override suspend fun disconnect() { connected = false }
    }

    private class FakeStore(var remotes: MutableList<RemoteBackup> = mutableListOf()) : CloudBackupStore {
        var uploadResult: CloudResult<Unit> = CloudResult.Success(Unit)
        var downloadResult: CloudResult<Unit> = CloudResult.Success(Unit)
        val deleted = mutableListOf<String>()
        override suspend fun upload(file: File, manifest: BackupManifest, onProgress: (Long, Long) -> Unit): CloudResult<RemoteBackup> {
            onProgress(1, 2)
            return when (val r = uploadResult) {
                is CloudResult.Success -> CloudResult.Success(RemoteBackup("new", file.name, manifest.createdAt, 100, manifest.deviceName, manifest.dbSchemaVersion, manifest.appVersionName).also { remotes += it })
                CloudResult.AuthRequired -> CloudResult.AuthRequired
                is CloudResult.Failure -> r
            }
        }
        override suspend fun list(): CloudResult<List<RemoteBackup>> = CloudResult.Success(remotes.toList())
        override suspend fun download(backup: RemoteBackup, target: File, onProgress: (Long, Long) -> Unit) = downloadResult
        override suspend fun delete(backup: RemoteBackup): CloudResult<Unit> { deleted += backup.id; remotes.remove(backup); return CloudResult.Success(Unit) }
    }

    private class FakeArchiver(var schema: Int = 29) : BackupArchiver {
        var manifest: BackupManifest? = null
        var restoreResult: ArchiveRestore = ArchiveRestore.Restored
        var restored = false
        override val currentSchemaVersion get() = schema
        override suspend fun create() = BackupArchive(File("a.zip"), BackupManifest(appVersionName = "1", appVersionCode = 1, dbSchemaVersion = schema, createdAt = 4_000, deviceName = "Pixel", documentCount = 1, fileCount = 1))
        override suspend fun readManifest(archive: File) = manifest
        override suspend fun restore(archive: File): ArchiveRestore { restored = true; return restoreResult }
    }

    private class FakeWorkspace : BackupWorkspace {
        val discarded = mutableListOf<String>()
        override fun newFile(name: String) = File(name)
        override fun discard(file: File) { discarded += file.name }
    }

    private class FakeStatus : BackupStatusStore {
        override val status: Flow<BackupStatus> = MutableStateFlow(BackupStatus())
        override val progress: Flow<BackupProgress?> = MutableStateFlow(null)
        val progressSeen = mutableListOf<BackupProgress?>()
        var success: Pair<Long, Long>? = null
        var failure: String? = null
        override fun setProgress(progress: BackupProgress?) { progressSeen += progress }
        override suspend fun recordSuccess(at: Long, sizeBytes: Long) { success = at to sizeBytes }
        override suspend fun recordFailure(at: Long, message: String) { failure = message }
    }

    private class FakeGate(var quiet: Boolean = true) : BackupGate {
        override suspend fun awaitQuiet(maxWaitMs: Long) = quiet
    }

    private class FakeRestarter : AppRestarter {
        var restarts = 0
        override fun restart() { restarts++ }
    }

    private val connection = FakeConnection()
    private val store = FakeStore()
    private val archiver = FakeArchiver()
    private val workspace = FakeWorkspace()
    private val status = FakeStatus()
    private val gate = FakeGate()
    private val restarter = FakeRestarter()

    private fun backUp() = BackUpNowUseCase(connection, archiver, workspace, store, gate, status, clock)
    private fun restore() = RestoreBackupUseCase(connection, archiver, workspace, store, status, restarter)

    @Test
    fun `a backup uploads, keeps the newest three, records the result and removes the local archive`() = runTest {
        store.remotes = mutableListOf(remote("o1", 100), remote("o2", 200), remote("o3", 300))
        val outcome = backUp()()
        assertThat(outcome).isInstanceOf(BackupOutcome.Done::class.java)
        assertThat(store.deleted).containsExactly("o1")
        assertThat(status.success).isEqualTo(5_000L to 100L)
        assertThat(workspace.discarded).containsExactly("a.zip")
        assertThat(status.progressSeen.last()).isNull()
    }

    @Test
    fun `a backup without a connected account does nothing`() = runTest {
        connection.connected = false
        assertThat(backUp()()).isEqualTo(BackupOutcome.NotConnected)
        assertThat(store.remotes).isEmpty()
    }

    @Test
    fun `the background run gives up when the phone stays busy, and does not pack or upload`() = runTest {
        gate.quiet = false
        assertThat(backUp()(waitForQuiet = true)).isEqualTo(BackupOutcome.Busy)
        assertThat(store.remotes).isEmpty()
        assertThat(status.success).isNull()
    }

    @Test
    fun `a manual backup does not wait for the gate`() = runTest {
        gate.quiet = false
        assertThat(backUp()(waitForQuiet = false)).isInstanceOf(BackupOutcome.Done::class.java)
    }

    @Test
    fun `an upload failure is recorded for Settings and nothing is deleted`() = runTest {
        store.remotes = mutableListOf(remote("o1", 100), remote("o2", 200), remote("o3", 300))
        store.uploadResult = CloudResult.Failure("quota")
        assertThat(backUp()()).isEqualTo(BackupOutcome.Failed("quota"))
        assertThat(status.failure).isEqualTo("quota")
        assertThat(store.deleted).isEmpty()
        assertThat(workspace.discarded).containsExactly("a.zip")
    }

    @Test
    fun `lost consent is reported as such and is not a recorded failure`() = runTest {
        store.uploadResult = CloudResult.AuthRequired
        assertThat(backUp()()).isEqualTo(BackupOutcome.AuthRequired)
        assertThat(status.failure).isNull()
    }

    @Test
    fun `a restore downloads, verifies, swaps and restarts`() = runTest {
        archiver.manifest = BackupManifest(appVersionName = "1", appVersionCode = 1, dbSchemaVersion = 28, createdAt = 1, deviceName = "d", documentCount = 0, fileCount = 0)
        assertThat(restore()(remote("r", 1))).isEqualTo(RestoreOutcome.Restarting)
        assertThat(archiver.restored).isTrue()
        assertThat(restarter.restarts).isEqualTo(1)
    }

    @Test
    fun `a backup the listing says is from a newer schema is refused before any download`() = runTest {
        store.downloadResult = CloudResult.Failure("must not be called")
        assertThat(restore()(remote("r", 1, schema = 30))).isEqualTo(RestoreOutcome.NewerThanApp(30, 29))
        assertThat(restarter.restarts).isEqualTo(0)
    }

    @Test
    fun `a newer schema found in the manifest is refused and nothing is swapped`() = runTest {
        archiver.manifest = BackupManifest(appVersionName = "2", appVersionCode = 2, dbSchemaVersion = 31, createdAt = 1, deviceName = "d", documentCount = 0, fileCount = 0)
        assertThat(restore()(remote("r", 1, schema = null))).isEqualTo(RestoreOutcome.NewerThanApp(31, 29))
        assertThat(archiver.restored).isFalse()
        assertThat(restarter.restarts).isEqualTo(0)
    }

    @Test
    fun `a file that is not a backup is refused`() = runTest {
        archiver.manifest = null
        assertThat(restore()(remote("r", 1))).isInstanceOf(RestoreOutcome.Failed::class.java)
        assertThat(archiver.restored).isFalse()
    }

    @Test
    fun `a swap that failed after the database was closed still restarts the app`() = runTest {
        archiver.manifest = BackupManifest(appVersionName = "1", appVersionCode = 1, dbSchemaVersion = 29, createdAt = 1, deviceName = "d", documentCount = 0, fileCount = 0)
        archiver.restoreResult = ArchiveRestore.FailedAfterClose("disk full")
        assertThat(restore()(remote("r", 1))).isEqualTo(RestoreOutcome.Failed("disk full"))
        assertThat(restarter.restarts).isEqualTo(1)
    }

    @Test
    fun `a refused archive changes nothing and does not restart`() = runTest {
        archiver.manifest = BackupManifest(appVersionName = "1", appVersionCode = 1, dbSchemaVersion = 29, createdAt = 1, deviceName = "d", documentCount = 0, fileCount = 0)
        archiver.restoreResult = ArchiveRestore.Refused("damaged")
        assertThat(restore()(remote("r", 1))).isEqualTo(RestoreOutcome.Failed("damaged"))
        assertThat(restarter.restarts).isEqualTo(0)
    }

    private class FakePrefs(var value: BackupSettings = BackupSettings()) : BackupPreferences {
        override val settings: Flow<BackupSettings> = MutableStateFlow(value)
        override suspend fun current() = value
        override suspend fun setAutoBackup(enabled: Boolean) { value = value.copy(autoBackup = enabled) }
        override suspend fun setWifiOnly(wifiOnly: Boolean) { value = value.copy(wifiOnly = wifiOnly) }
    }

    private class FakeScheduler : BackupScheduler {
        var scheduledWifiOnly: Boolean? = null
        var cancelled = 0
        override fun schedule(wifiOnly: Boolean) { scheduledWifiOnly = wifiOnly }
        override fun cancel() { cancelled++ }
        override fun runNow() = Unit
    }

    @Test
    fun `the daily job is scheduled only while connected and switched on, with the Wi-Fi choice`() = runTest {
        val prefs = FakePrefs(BackupSettings(autoBackup = true, wifiOnly = false))
        val scheduler = FakeScheduler()
        val schedule = ScheduleAutoBackupUseCase(connection, prefs, scheduler)
        schedule()
        assertThat(scheduler.scheduledWifiOnly).isFalse()
        prefs.value = prefs.value.copy(autoBackup = false)
        schedule()
        assertThat(scheduler.cancelled).isEqualTo(1)
        prefs.value = prefs.value.copy(autoBackup = true)
        connection.connected = false
        schedule()
        assertThat(scheduler.cancelled).isEqualTo(2)
    }

    @Test
    fun `connecting for the first time switches the daily backup on, and disconnecting switches it off and cancels the job`() = runTest {
        connection.connected = false
        val prefs = FakePrefs()
        val scheduler = FakeScheduler()
        ConnectDriveUseCase(connection, prefs, ScheduleAutoBackupUseCase(connection, prefs, scheduler))()
        assertThat(prefs.value.autoBackup).isTrue()
        assertThat(scheduler.scheduledWifiOnly).isTrue()
        DisconnectDriveUseCase(connection, prefs, scheduler)()
        assertThat(prefs.value.autoBackup).isFalse()
        assertThat(connection.connected).isFalse()
        assertThat(scheduler.cancelled).isEqualTo(1)
    }
}
