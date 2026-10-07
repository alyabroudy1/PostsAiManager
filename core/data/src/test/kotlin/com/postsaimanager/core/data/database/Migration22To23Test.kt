package com.postsaimanager.core.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * `MIGRATION_22_23` run for real on SQLite (Robolectric), against a minimal v22 schema of the tables the new ones refer to: the three
 * tables and their indices arrive empty, a second run changes nothing, the foreign keys behave as declared, and the statements are
 * exactly what Room exported for version 23 (so the instrumented validation cannot disagree). The instrumented `MigrationTest` validates
 * the same step against the exported schema on a device.
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
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE `contact_persons` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
    }

    @After
    fun close() = helper.close()

    private fun strings(sql: String): List<String> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun migrate() = PamMigrations.MIGRATION_22_23.migrate(db)

    @Test
    fun `the migration is the step from 22 to 23 and is registered`() {
        assertThat(PamMigrations.MIGRATION_22_23.startVersion).isEqualTo(22)
        assertThat(PamMigrations.MIGRATION_22_23.endVersion).isEqualTo(23)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_22_23)
    }

    @Test
    fun `the three tables and their indices arrive empty`() {
        migrate()

        listOf("cases", "profile_events", "profile_event_people").forEach { table ->
            assertThat(strings("SELECT count(*) FROM `$table`")).containsExactly("0")
        }
        assertThat(strings("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%' ORDER BY name")).containsExactly(
            "index_cases_organisationProfileId",
            "index_profile_event_people_eventId",
            "index_profile_event_people_profileId",
            "index_profile_events_caseId",
            "index_profile_events_contactId",
            "index_profile_events_documentId",
            "index_profile_events_eventDate",
            "index_profile_events_organisationProfileId",
        )
    }

    @Test
    fun `it leaves the document processing log table alone and a second run changes nothing`() {
        db.execSQL("CREATE TABLE `timeline_events` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO timeline_events VALUES ('log')")
        migrate()
        db.execSQL("INSERT INTO documents VALUES ('d1', 'x')")
        db.execSQL("INSERT INTO profiles VALUES ('jc', 'Jobcenter')")
        db.execSQL("INSERT INTO cases VALUES ('c1', 'jc', 'Matter', '|AZ1234|', 'OPEN', 1)")
        val before = strings("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")

        migrate()

        assertThat(strings("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")).isEqualTo(before)
        assertThat(strings("SELECT id FROM cases")).containsExactly("c1")
        assertThat(strings("SELECT id FROM timeline_events")).containsExactly("log")
    }

    @Test
    fun `the foreign keys are as declared`() {
        migrate()
        db.execSQL("INSERT INTO documents VALUES ('d1', 'x')")
        db.execSQL("INSERT INTO profiles VALUES ('jc', 'Jobcenter')")
        db.execSQL("INSERT INTO profiles VALUES ('maria', 'Maria')")
        db.execSQL("INSERT INTO cases VALUES ('c1', 'jc', 'Matter', '', 'OPEN', 1)")
        db.execSQL("INSERT INTO profile_events VALUES ('e1', 'd1', 'approval', 5, 6, 'T', 'jc', NULL, 'c1', 'DOCUMENT')")
        db.execSQL("INSERT INTO profile_event_people VALUES ('e1', 'maria')")

        // The organisation goes: its matter goes, the event stays and only loses the links.
        db.execSQL("DELETE FROM profiles WHERE id = 'jc'")
        assertThat(strings("SELECT id FROM cases")).isEmpty()
        assertThat(strings("SELECT coalesce(organisationProfileId, 'null') || '/' || coalesce(caseId, 'null') FROM profile_events")).containsExactly("null/null")
        assertThat(strings("SELECT profileId FROM profile_event_people")).containsExactly("maria")

        // The person goes: the link goes. The document goes: the event and its links go.
        db.execSQL("DELETE FROM profiles WHERE id = 'maria'")
        assertThat(strings("SELECT profileId FROM profile_event_people")).isEmpty()
    }

    @Test
    fun `deleting the document deletes its events and their people`() {
        migrate()
        db.execSQL("INSERT INTO documents VALUES ('d1', 'x')")
        db.execSQL("INSERT INTO profiles VALUES ('maria', 'Maria')")
        db.execSQL("INSERT INTO profile_events VALUES ('e1', 'd1', 'approval', 5, 6, 'T', NULL, NULL, NULL, 'DOCUMENT')")
        db.execSQL("INSERT INTO profile_event_people VALUES ('e1', 'maria')")

        db.execSQL("DELETE FROM documents WHERE id = 'd1'")

        assertThat(strings("SELECT id FROM profile_events")).isEmpty()
        assertThat(strings("SELECT eventId FROM profile_event_people")).isEmpty()
    }

    @Test
    fun `the statements are exactly what Room exported for version 23`() {
        val schema = File("schemas/com.postsaimanager.core.data.database.PamDatabase/23.json")
        assertThat(schema.exists()).isTrue()
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        val exported = entities.map { it.jsonObject }.filter { it.string("tableName") in setOf("cases", "profile_events", "profile_event_people") }
            .flatMap { entity ->
                val table = entity.string("tableName")
                listOf(entity.string("createSql").replace("\${TABLE_NAME}", table)) +
                    entity.getValue("indices").jsonArray.map { it.jsonObject.string("createSql").replace("\${TABLE_NAME}", table) }
            }
        // Room writes `CREATE TABLE IF NOT EXISTS`/`CREATE INDEX IF NOT EXISTS` itself; the migration's statements must be the same text.
        assertThat(TimelineMigration.statements).containsExactlyElementsIn(exported)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
