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
 * `MIGRATION_26_27` run for real on SQLite (Robolectric) against minimal v26 `profiles` and `contact_persons` tables: the two
 * `customDetails` columns and the `profile_suggestions` table arrive, existing rows keep their data and read "no details", and a second
 * run (or a database that already has the columns) changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration26To27Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV26() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE `contact_persons` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("INSERT INTO profiles VALUES ('jc', 'Jobcenter Musterstadt')")
        db.execSQL("INSERT INTO contact_persons VALUES ('c1', 'Frau Beispiel')")
    }

    @After
    fun close() = helper.close()

    private fun columns(table: String): List<String> = db.query("SELECT name FROM pragma_table_info('$table') ORDER BY cid").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun tables(): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun indices(): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'profile_suggestions'").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    @Test
    fun `the migration is the step from 26 to 27 and is registered last`() {
        assertThat(PamMigrations.MIGRATION_26_27.startVersion).isEqualTo(26)
        assertThat(PamMigrations.MIGRATION_26_27.endVersion).isEqualTo(27)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_26_27)
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isAtLeast(27)
    }

    @Test
    fun `the columns and the table arrive and the rows are kept with no details`() {
        PamMigrations.MIGRATION_26_27.migrate(db)

        assertThat(columns("profiles")).containsExactly("id", "name", "customDetails").inOrder()
        assertThat(columns("contact_persons")).containsExactly("id", "name", "customDetails").inOrder()
        assertThat(tables()).contains("profile_suggestions")
        assertThat(columns("profile_suggestions"))
            .containsExactly("id", "profileId", "field", "value", "sourceDocumentId", "createdAt", "status").inOrder()
        assertThat(indices()).containsAtLeast("index_profile_suggestions_profileId", "index_profile_suggestions_profileId_field_value")
        db.query("SELECT name, customDetails FROM profiles WHERE id = 'jc'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Jobcenter Musterstadt")
            assertThat(c.isNull(1)).isTrue()
        }
        db.query("SELECT name, customDetails FROM contact_persons WHERE id = 'c1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Frau Beispiel")
            assertThat(c.isNull(1)).isTrue()
        }
    }

    @Test
    fun `running it again keeps the details and the suggestions that were stored`() {
        PamMigrations.MIGRATION_26_27.migrate(db)
        db.execSQL("UPDATE profiles SET customDetails = '[{\"label\":\"Customer number\",\"value\":\"4711\"}]' WHERE id = 'jc'")
        db.execSQL("INSERT INTO profile_suggestions VALUES ('s1', 'jc', 'PHONE', '0800 555 0199', 'd1', 1, 'PENDING')")

        PamMigrations.MIGRATION_26_27.migrate(db)

        assertThat(columns("profiles").count { it == "customDetails" }).isEqualTo(1)
        assertThat(columns("contact_persons").count { it == "customDetails" }).isEqualTo(1)
        db.query("SELECT customDetails FROM profiles WHERE id = 'jc'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).contains("Customer number")
        }
        db.query("SELECT COUNT(*) FROM profile_suggestions").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
    }
}
