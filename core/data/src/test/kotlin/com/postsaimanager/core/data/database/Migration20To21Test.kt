package com.postsaimanager.core.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `MIGRATION_20_21` run for real on SQLite (Robolectric), against a minimal v20 `documents` table: the existing rows survive, the two
 * import columns arrive empty, and running it again, or on a database that already has a column, does not fail. The instrumented
 * `MigrationTest` validates the same step against the exported schema on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration20To21Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV20() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `concernedProfileIds` TEXT, PRIMARY KEY(`id`))")
                        db.execSQL("INSERT INTO `documents` (`id`, `title`) VALUES ('doc-1', 'Rechnung')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
    }

    @After
    fun close() = helper.close()

    private fun columns(): Set<String> = db.query("PRAGMA table_info(`documents`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        generateSequence { if (c.moveToNext()) c.getString(name) else null }.toSet()
    }

    @Test
    fun `the migration is the step from 20 to 21 and is registered`() {
        assertThat(PamMigrations.MIGRATION_20_21.startVersion).isEqualTo(20)
        assertThat(PamMigrations.MIGRATION_20_21.endVersion).isEqualTo(21)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_20_21)
    }

    @Test
    fun `existing documents survive and gain empty import columns`() {
        PamMigrations.MIGRATION_20_21.migrate(db)

        assertThat(columns()).containsAtLeast("sourceHash", "originalFilePath")
        db.query("SELECT title, sourceHash, originalFilePath FROM documents WHERE id = 'doc-1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Rechnung")
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.isNull(2)).isTrue()
        }
    }

    @Test
    fun `running it twice, or over a column that already exists, keeps the data and does not fail`() {
        db.execSQL("ALTER TABLE `documents` ADD COLUMN `sourceHash` TEXT")
        db.execSQL("UPDATE documents SET sourceHash = 'abc' WHERE id = 'doc-1'")

        PamMigrations.MIGRATION_20_21.migrate(db)
        PamMigrations.MIGRATION_20_21.migrate(db)

        db.query("SELECT sourceHash, originalFilePath FROM documents WHERE id = 'doc-1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("abc")
            assertThat(c.isNull(1)).isTrue()
        }
    }
}
