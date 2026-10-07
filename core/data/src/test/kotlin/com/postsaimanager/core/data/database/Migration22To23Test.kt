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
 * `MIGRATION_22_23` run for real on SQLite (Robolectric), against a minimal v22 schema: the table `document_notes` arrives empty with
 * the columns the entity declares, its notes go with their document, and a second run (a database that already has the table) changes
 * nothing. The instrumented `MigrationTest` validates the same step against the exported schema on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration22To23Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV22() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("PRAGMA foreign_keys=ON")
        listOf("d1", "d2").forEach { db.execSQL("INSERT INTO documents (id, title) VALUES ('$it', '$it')") }
    }

    @After
    fun close() = helper.close()

    private fun migrate() = PamMigrations.MIGRATION_22_23.migrate(db)

    private fun strings(sql: String): List<String> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun insertNote(id: String, documentId: String, ref: String? = null) = db.execSQL(
        "INSERT INTO document_notes (id, documentId, text, source, createdAt, updatedAt, pinned, sourceRef) VALUES (?, ?, 'a note', 'AI', 1, 2, 0, ?)",
        arrayOf<Any?>(id, documentId, ref),
    )

    @Test
    fun `the migration is the step from 22 to 23 and is registered`() {
        assertThat(PamMigrations.MIGRATION_22_23.startVersion).isEqualTo(22)
        assertThat(PamMigrations.MIGRATION_22_23.endVersion).isEqualTo(23)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_22_23)
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isEqualTo(23)
    }

    @Test
    fun `the table arrives empty with the columns of the entity and its index`() {
        migrate()

        assertThat(strings("SELECT count(*) FROM document_notes")).containsExactly("0")
        assertThat(strings("SELECT name FROM pragma_table_info('document_notes') ORDER BY cid"))
            .containsExactly("id", "documentId", "text", "source", "createdAt", "updatedAt", "pinned", "sourceRef").inOrder()
        assertThat(strings("SELECT name FROM pragma_index_list('document_notes')")).contains("index_document_notes_documentId")
    }

    @Test
    fun `notes go with their document`() {
        migrate()
        insertNote("n1", "d1")
        insertNote("n2", "d2", ref = "card-1")

        db.execSQL("DELETE FROM documents WHERE id = 'd1'")

        assertThat(strings("SELECT id FROM document_notes")).containsExactly("n2")
    }

    @Test
    fun `a note of no document is refused`() {
        migrate()

        val failed = runCatching { insertNote("n1", "missing") }.isFailure

        assertThat(failed).isTrue()
    }

    @Test
    fun `running it twice, or over a table that already exists, keeps the notes and does not fail`() {
        migrate()
        insertNote("n1", "d1", ref = "card-1")

        migrate()

        assertThat(strings("SELECT id FROM document_notes")).containsExactly("n1")
        assertThat(strings("SELECT count(*) FROM pragma_index_list('document_notes') WHERE name = 'index_document_notes_documentId'"))
            .containsExactly("1")
    }
}
