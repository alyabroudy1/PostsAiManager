package com.postsaimanager.core.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `MIGRATION_24_25` run for real on SQLite (Robolectric), against a minimal v24 schema (the notes table as version 23 created it, and the
 * tables its foreign keys point at): the rows of `document_notes` are kept with a null person, `documentId` becomes nullable, `profileId`
 * and its index arrive, a second run changes nothing, the foreign keys behave as declared, and the statements are exactly what Room
 * exported for version 25. The instrumented `MigrationTest` validates the same step against the exported schemas on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration24To25Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV24() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        // The notes table exactly as version 23 created it.
                        PamMigrations.MIGRATION_22_23.migrate(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("INSERT INTO documents VALUES ('d1', 'Letter')")
        db.execSQL("INSERT INTO profiles VALUES ('maria', 'Maria')")
    }

    @After
    fun close() = helper.close()

    private fun strings(sql: String): List<String> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun migrate() = PamMigrations.MIGRATION_24_25.migrate(db)

    private fun v24Note(id: String, documentId: String = "d1", pinned: Int = 0, ref: String? = null) = db.execSQL(
        "INSERT INTO document_notes (id, documentId, text, source, createdAt, updatedAt, pinned, sourceRef) VALUES (?, ?, 'a note', 'AI', 1, 2, ?, ?)",
        arrayOf<Any?>(id, documentId, pinned, ref),
    )

    @Test
    fun `the migration is the step from 24 to 25 and is registered, with later steps only added after it`() {
        assertThat(PamMigrations.MIGRATION_24_25.startVersion).isEqualTo(24)
        assertThat(PamMigrations.MIGRATION_24_25.endVersion).isEqualTo(25)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_24_25)
        // v26 (the reading stage) is the latest step and chains from 25.
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isEqualTo(26)
        assertThat(PamMigrations.MIGRATION_25_26.startVersion).isEqualTo(25)
    }

    @Test
    fun `the notes are kept, with no person, and the table has the columns and indices of the entity`() {
        v24Note("n1", pinned = 1, ref = "card-1")
        v24Note("n2")

        migrate()

        assertThat(strings("SELECT name FROM pragma_table_info('document_notes') ORDER BY cid"))
            .containsExactly("id", "documentId", "profileId", "text", "source", "createdAt", "updatedAt", "pinned", "sourceRef").inOrder()
        assertThat(strings("SELECT id || '/' || documentId || '/' || ifnull(profileId, 'null') || '/' || pinned || '/' || ifnull(sourceRef, 'null') FROM document_notes ORDER BY id"))
            .containsExactly("n1/d1/null/1/card-1", "n2/d1/null/0/null").inOrder()
        assertThat(strings("SELECT name FROM pragma_index_list('document_notes') WHERE name LIKE 'index_%' ORDER BY name"))
            .containsExactly("index_document_notes_documentId", "index_document_notes_profileId").inOrder()
        assertThat(strings("SELECT name FROM sqlite_master WHERE name LIKE 'document_notes%'")).doesNotContain("document_notes_v25")
    }

    @Test
    fun `documentId is nullable now, so a note can belong to a person or to the household`() {
        migrate()

        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('p', NULL, 'maria', 'about Maria', 'AI', 1, 1, 0)")
        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('h', NULL, NULL, 'household', 'AI', 1, 1, 0)")

        assertThat(strings("SELECT id FROM document_notes WHERE documentId IS NULL ORDER BY id")).containsExactly("h", "p").inOrder()
    }

    @Test
    fun `a second run changes nothing`() {
        v24Note("n1")
        migrate()
        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('p', NULL, 'maria', 'about Maria', 'AI', 1, 1, 0)")
        val before = strings("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")

        migrate()

        assertThat(strings("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")).isEqualTo(before)
        assertThat(strings("SELECT id FROM document_notes ORDER BY id")).containsExactly("n1", "p").inOrder()
    }

    @Test
    fun `an earlier run that stopped half way, and a database without the table, both end in the same table`() {
        // A temporary table left behind by a run that did not finish is started over.
        db.execSQL("CREATE TABLE `document_notes_v25` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
        v24Note("n1")
        migrate()
        assertThat(strings("SELECT id FROM document_notes")).containsExactly("n1")
        assertThat(strings("SELECT name FROM sqlite_master WHERE name = 'document_notes_v25'")).isEmpty()

        // No table at all (a database that never had version 23's step): it is simply created.
        db.execSQL("DROP TABLE document_notes")
        migrate()
        assertThat(strings("SELECT name FROM pragma_table_info('document_notes') ORDER BY cid")).contains("profileId")
        assertThat(strings("SELECT count(*) FROM document_notes")).containsExactly("0")
    }

    @Test
    fun `the foreign keys are as declared, the document and the profile take their notes with them and the household's stay`() {
        migrate()
        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('d', 'd1', NULL, 'of a letter', 'AI', 1, 1, 0)")
        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('p', NULL, 'maria', 'about Maria', 'AI', 1, 1, 0)")
        db.execSQL("INSERT INTO document_notes (id, documentId, profileId, text, source, createdAt, updatedAt, pinned) VALUES ('h', NULL, NULL, 'household', 'AI', 1, 1, 0)")

        db.execSQL("DELETE FROM documents WHERE id = 'd1'")
        assertThat(strings("SELECT id FROM document_notes ORDER BY id")).containsExactly("h", "p").inOrder()

        db.execSQL("DELETE FROM profiles WHERE id = 'maria'")
        assertThat(strings("SELECT id FROM document_notes")).containsExactly("h")
    }

    @Test
    fun `the chain 22 to 23 to 24 to 25 keeps the notes and is repeatable`() {
        v24Note("n1")
        PamMigrations.MIGRATION_22_23.migrate(db)
        migrate()
        PamMigrations.MIGRATION_22_23.migrate(db)
        migrate()

        assertThat(strings("SELECT id FROM document_notes")).containsExactly("n1")
        assertThat(strings("SELECT name FROM pragma_table_info('document_notes') ORDER BY cid")).contains("profileId")
    }

    @Test
    fun `the statements are exactly what Room exported for version 25`() {
        val schema = File("schemas/com.postsaimanager.core.data.database.PamDatabase/25.json")
        assertThat(schema.exists()).isTrue()
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        val notes = entities.map { it.jsonObject }.single { it.string("tableName") == "document_notes" }
        val exported = listOf(notes.string("createSql").replace("\${TABLE_NAME}", "document_notes")) +
            notes.getValue("indices").jsonArray.map { it.jsonObject.string("createSql").replace("\${TABLE_NAME}", "document_notes") }

        assertThat(NotesPerPersonMigration.statements).containsExactlyElementsIn(exported).inOrder()
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
