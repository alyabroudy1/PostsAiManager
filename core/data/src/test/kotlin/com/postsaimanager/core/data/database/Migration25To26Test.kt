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
 * `MIGRATION_25_26` run for real on SQLite (Robolectric), against a minimal v25 `documents` table: the column `readingStage` arrives, every
 * existing row keeps its data and reads null ("finished"), a second run (or a database that already has the column) changes nothing. The
 * instrumented `MigrationTest` validates the same step against the exported schemas on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration25To26Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV25() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("INSERT INTO documents VALUES ('d1', 'Letter', 'EXTRACTED')")
    }

    @After
    fun close() = helper.close()

    private fun columns(): List<String> = db.query("SELECT name FROM pragma_table_info('documents') ORDER BY cid").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    @Test
    fun `the migration is the step from 25 to 26 and is registered last`() {
        assertThat(PamMigrations.MIGRATION_25_26.startVersion).isEqualTo(25)
        assertThat(PamMigrations.MIGRATION_25_26.endVersion).isEqualTo(26)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_25_26)
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isAtLeast(26)
    }

    @Test
    fun `the column arrives, the documents are kept and read as finished`() {
        PamMigrations.MIGRATION_25_26.migrate(db)

        assertThat(columns()).containsExactly("id", "title", "status", "readingStage").inOrder()
        db.query("SELECT title, status, readingStage FROM documents WHERE id = 'd1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Letter")
            assertThat(c.getString(1)).isEqualTo("EXTRACTED")
            assertThat(c.isNull(2)).isTrue()
        }
        db.execSQL("UPDATE documents SET readingStage = 'FIELDS_READY' WHERE id = 'd1'")
        db.query("SELECT readingStage FROM documents").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("FIELDS_READY")
        }
    }

    @Test
    fun `running it again, or on a database that already has the column, changes nothing`() {
        PamMigrations.MIGRATION_25_26.migrate(db)
        db.execSQL("UPDATE documents SET readingStage = 'UNDERSTOOD' WHERE id = 'd1'")

        PamMigrations.MIGRATION_25_26.migrate(db)

        assertThat(columns().count { it == "readingStage" }).isEqualTo(1)
        db.query("SELECT readingStage FROM documents").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("UNDERSTOOD")
        }
    }
}
