package com.postsaimanager.core.domain.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The manifest, the version checks of a restore, and which backups Drive keeps. */
class BackupRulesTest {

    private fun manifest(schema: Int = 29, format: Int = BackupManifest.FORMAT_VERSION) = BackupManifest(
        formatVersion = format,
        appVersionName = "1.0.0",
        appVersionCode = 1,
        dbSchemaVersion = schema,
        createdAt = 1_000L,
        deviceName = "Pixel",
        documentCount = 3,
        fileCount = 7,
    )

    private fun remote(id: String, createdAt: Long) =
        RemoteBackup(id, "pam-backup-$createdAt.zip", createdAt, sizeBytes = 10, deviceName = "Pixel", dbSchemaVersion = 29, appVersionName = "1.0.0")

    @Test
    fun `a manifest survives its own JSON, and a text that is no manifest is null`() {
        assertThat(BackupManifest.fromJson(manifest().toJson())).isEqualTo(manifest())
        assertThat(BackupManifest.fromJson("not json")).isNull()
        assertThat(BackupManifest.fromJson("""{"formatVersion":1,"future":"field","appVersionName":"1","appVersionCode":1,"dbSchemaVersion":2,"createdAt":1,"deviceName":"d","documentCount":0,"fileCount":0}"""))
            .isNotNull()
    }

    @Test
    fun `the same or an older schema is compatible, a newer one is refused`() {
        assertThat(BackupCompatibility.check(manifest(schema = 29), appSchemaVersion = 29)).isEqualTo(BackupCompatibility.Compatible)
        assertThat(BackupCompatibility.check(manifest(schema = 20), appSchemaVersion = 29)).isEqualTo(BackupCompatibility.Compatible)
        assertThat(BackupCompatibility.check(manifest(schema = 30), appSchemaVersion = 29)).isEqualTo(BackupCompatibility.NewerThanApp(30, 29))
    }

    @Test
    fun `an unknown archive format is refused`() {
        assertThat(BackupCompatibility.check(manifest(format = 99), appSchemaVersion = 29)).isEqualTo(BackupCompatibility.UnsupportedFormat(99))
        assertThat(BackupCompatibility.check(manifest(format = 0), appSchemaVersion = 29)).isEqualTo(BackupCompatibility.UnsupportedFormat(0))
    }

    @Test
    fun `the newest three backups are kept and the rest deleted`() {
        val all = (1L..5L).map { remote("id$it", createdAt = it * 100) }.shuffled()
        assertThat(BackupRetention.toDelete(all).map { it.id }).containsExactly("id2", "id1")
    }

    @Test
    fun `three or fewer backups delete nothing, and the one just made is never deleted`() {
        assertThat(BackupRetention.toDelete(listOf(remote("a", 1), remote("b", 2), remote("c", 3)))).isEmpty()
        // A clock that went backwards: the new upload looks oldest, but it must stay.
        val all = listOf(remote("new", 1), remote("a", 2), remote("b", 3), remote("c", 4))
        assertThat(BackupRetention.toDelete(all, protectedId = "new")).isEmpty()
    }
}
