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
 * `MIGRATION_21_22` run for real on SQLite (Robolectric), against a minimal v21 schema of the tables it touches: the old `type`
 * becomes kind and household role, former caseworker profiles move into their organisation (with their documents and tombstones),
 * anything ambiguous stays a profile, and a second run changes nothing. The instrumented `MigrationTest` validates the same step
 * against the exported schema on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration21To22Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV21() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL(
                            "CREATE TABLE `profiles` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `organization` TEXT, " +
                                "`department` TEXT, `street` TEXT, `city` TEXT, `postalCode` TEXT, `country` TEXT, `phone` TEXT, `email` TEXT, " +
                                "`website` TEXT, `reference` TEXT, `notes` TEXT, `completionScore` REAL NOT NULL DEFAULT 0, `missingFields` TEXT, " +
                                "`avatarPath` TEXT, `createdAt` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `sourceDocumentId` TEXT, " +
                                "`sourceEntityName` TEXT, `relationship` TEXT, `birthDate` TEXT, `sensitive` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `document_profile_links` (`documentId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`documentId`, `profileId`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `profile_facts` (`id` TEXT NOT NULL, `profileId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                                "`value` TEXT NOT NULL, PRIMARY KEY(`id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE `dismissed_entities` (`documentId` TEXT NOT NULL, `entityName` TEXT NOT NULL, " +
                                "`dismissedAt` INTEGER NOT NULL, PRIMARY KEY(`documentId`, `entityName`))",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        listOf("d1", "d2", "d3").forEach { db.execSQL("INSERT INTO documents (id, title) VALUES ('$it', '$it')") }
    }

    @After
    fun close() = helper.close()

    private fun profile(
        id: String,
        type: String,
        name: String = id,
        organization: String? = null,
        sourceDocumentId: String? = null,
        sourceEntityName: String? = null,
        street: String? = null,
        sensitive: Int = 0,
    ) {
        db.execSQL(
            "INSERT INTO profiles (id, type, name, organization, department, phone, email, street, createdAt, modifiedAt, " +
                "sourceDocumentId, sourceEntityName, sensitive) VALUES (?, ?, ?, ?, 'Team 5', '030 123', 'n@jc.de', ?, 7, 7, ?, ?, ?)",
            arrayOf<Any?>(id, type, name, organization, street, sourceDocumentId, sourceEntityName, sensitive),
        )
    }

    private fun link(profileId: String, documentId: String, role: String, createdAt: Long = 100) =
        db.execSQL("INSERT INTO document_profile_links VALUES (?, ?, ?, ?)", arrayOf<Any?>(documentId, profileId, role, createdAt))

    private fun strings(sql: String): List<String> = db.query(sql).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun profileIds() = strings("SELECT id FROM profiles ORDER BY id")

    private fun migrate() = PamMigrations.MIGRATION_21_22.migrate(db)

    @Test
    fun `the migration is the step from 21 to 22 and is registered`() {
        assertThat(PamMigrations.MIGRATION_21_22.startVersion).isEqualTo(21)
        assertThat(PamMigrations.MIGRATION_21_22.endVersion).isEqualTo(22)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_21_22)
    }

    @Test
    fun `the old type becomes kind and household role, and type stays readable`() {
        profile("org", "AUTHORITY")
        profile("person", "PERSON")
        profile("member", "FAMILY_MEMBER")
        profile("me", "USER_SELF")

        migrate()

        val rows = db.query("SELECT id, kind, householdRole, type FROM profiles ORDER BY id").use { c ->
            generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList()
        }
        assertThat(rows).containsExactly(
            listOf("me", "PERSON", "SELF", "USER_SELF"),
            listOf("member", "PERSON", "MEMBER", "FAMILY_MEMBER"),
            listOf("org", "ORGANISATION", null, "AUTHORITY"),
            listOf("person", "PERSON", null, "PERSON"),
        ).inOrder()
    }

    @Test
    fun `the three new tables arrive empty`() {
        migrate()

        listOf("contact_persons", "document_contacts", "organisation_references").forEach { table ->
            assertThat(strings("SELECT count(*) FROM `$table`")).containsExactly("0")
        }
    }

    @Test
    fun `a caseworker profile becomes a contact of its organisation with its documents and tombstones`() {
        profile("jc", "AUTHORITY", name = "Jobcenter Musterstadt")
        profile("mueller", "PERSON", name = "Frau Müller", organization = "Jobcenter Musterstadt", sourceDocumentId = "d1", sourceEntityName = "frau müller")
        link("mueller", "d1", "CASE_WORKER", createdAt = 100)
        link("mueller", "d2", "CASE_WORKER", createdAt = 200)
        link("jc", "d1", "SENDER")

        migrate()

        assertThat(profileIds()).containsExactly("jc")
        db.query("SELECT id, organisationId, name, department, phone, email, firstSeen, lastSeen, active FROM contact_persons").use { c ->
            assertThat(c.count).isEqualTo(1)
            c.moveToFirst()
            assertThat(c.getString(0)).isEqualTo("mueller")
            assertThat(c.getString(1)).isEqualTo("jc")
            assertThat(c.getString(2)).isEqualTo("Frau Müller")
            assertThat(c.getString(3)).isEqualTo("Team 5")
            assertThat(c.getString(4)).isEqualTo("030 123")
            assertThat(c.getString(5)).isEqualTo("n@jc.de")
            assertThat(c.getLong(6)).isEqualTo(100)
            assertThat(c.getLong(7)).isEqualTo(200)
            assertThat(c.getInt(8)).isEqualTo(1)
        }
        assertThat(strings("SELECT documentId FROM document_contacts WHERE contactId = 'mueller' ORDER BY documentId")).containsExactly("d1", "d2").inOrder()
        assertThat(strings("SELECT count(*) FROM document_profile_links WHERE profileId = 'mueller'")).containsExactly("0")
        // The organisation's own link is untouched.
        assertThat(strings("SELECT documentId FROM document_profile_links WHERE profileId = 'jc'")).containsExactly("d1")
        // Tombstones: the source document under the key it was created with, the other under the profile's own name.
        assertThat(strings("SELECT documentId || ':' || entityName FROM dismissed_entities ORDER BY documentId"))
            .containsExactly("d1:frau müller", "d2:frau müller").inOrder()
    }

    @Test
    fun `anything ambiguous stays a profile`() {
        // Two offices with the same name: which one is it?
        profile("office-a", "AUTHORITY", name = "Jobcenter Musterstadt")
        profile("office-b", "AUTHORITY", name = "Jobcenter Musterstadt")
        profile("two-offices", "PERSON", organization = "Jobcenter Musterstadt")
        link("two-offices", "d1", "CASE_WORKER")
        // No organisation profile of that name.
        profile("no-org", "PERSON", organization = "Finanzamt Nirgendwo")
        link("no-org", "d1", "CASE_WORKER")
        // And one organisation that matches, for the profiles below.
        profile("tax", "AUTHORITY", name = "Finanzamt")
        // Also linked as something other than a caseworker.
        profile("also-sender", "PERSON", organization = "Finanzamt")
        link("also-sender", "d1", "CASE_WORKER")
        link("also-sender", "d2", "RELATED")
        // Not linked to any document.
        profile("no-links", "PERSON", organization = "Finanzamt")
        // Carries an address a contact cannot hold.
        profile("with-address", "PERSON", organization = "Finanzamt", street = "Hauptstr. 1")
        link("with-address", "d1", "CASE_WORKER")
        // Part of the household.
        profile("member", "FAMILY_MEMBER", organization = "Finanzamt")
        link("member", "d1", "CASE_WORKER")
        // Marked sensitive.
        profile("sensitive", "PERSON", organization = "Finanzamt", sensitive = 1)
        link("sensitive", "d1", "CASE_WORKER")
        // Has saved details.
        profile("with-facts", "PERSON", organization = "Finanzamt")
        link("with-facts", "d1", "CASE_WORKER")
        db.execSQL("INSERT INTO profile_facts VALUES ('f1', 'with-facts', 'allergies', 'nuts')")
        // A near miss of the name is not the same organisation.
        profile("near-miss", "PERSON", organization = "Finanzamt Mitte")
        link("near-miss", "d1", "CASE_WORKER")
        val before = profileIds()

        migrate()

        assertThat(profileIds()).isEqualTo(before)
        assertThat(strings("SELECT count(*) FROM contact_persons")).containsExactly("0")
        assertThat(strings("SELECT count(*) FROM dismissed_entities")).containsExactly("0")
        assertThat(strings("SELECT count(*) FROM document_profile_links")).containsExactly("9")
    }

    @Test
    fun `running it twice, or over columns that already exist, keeps the data and does not fail`() {
        profile("jc", "AUTHORITY", name = "Jobcenter Musterstadt")
        profile("mueller", "PERSON", name = "Frau Müller", organization = "Jobcenter Musterstadt")
        link("mueller", "d1", "CASE_WORKER")
        profile("anna", "PERSON")

        migrate()
        // After the first run a person was reclassified by the user; a repeated run must not undo that.
        db.execSQL("UPDATE profiles SET householdRole = 'MEMBER' WHERE id = 'anna'")
        migrate()

        assertThat(profileIds()).containsExactly("anna", "jc")
        assertThat(strings("SELECT householdRole FROM profiles WHERE id = 'anna'")).containsExactly("MEMBER")
        assertThat(strings("SELECT id FROM contact_persons")).containsExactly("mueller")
        assertThat(strings("SELECT count(*) FROM document_contacts")).containsExactly("1")
        assertThat(strings("SELECT count(*) FROM dismissed_entities")).containsExactly("1")
    }
}
