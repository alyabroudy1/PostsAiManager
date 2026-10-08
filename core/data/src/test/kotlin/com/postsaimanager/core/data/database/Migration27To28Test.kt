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
 * `MIGRATION_27_28` run for real on SQLite (Robolectric), against a minimal v27 `cases` table: the column `titleSource` arrives and every
 * existing matter reads `AUTO` (no rename was ever recorded), the rows keep their data, and a second run changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration27To28Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV27() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE `cases` (`id` TEXT NOT NULL, `organisationProfileId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                                "`referenceKeys` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("INSERT INTO cases VALUES ('c1', 'o1', 'Appointment reminder · Z Zahnarztpraxis', '', 'OPEN', 5)")
    }

    @After
    fun close() = helper.close()

    private fun columns(): List<String> = db.query("SELECT name FROM pragma_table_info('cases') ORDER BY cid").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    @Test
    fun `the migration is the step from 27 to 28 and is registered`() {
        assertThat(PamMigrations.MIGRATION_27_28.startVersion).isEqualTo(27)
        assertThat(PamMigrations.MIGRATION_27_28.endVersion).isEqualTo(28)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_27_28)
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isAtLeast(28)
    }

    @Test
    fun `the column arrives, the matters are kept and every one is AUTO`() {
        PamMigrations.MIGRATION_27_28.migrate(db)

        assertThat(columns()).containsExactly("id", "organisationProfileId", "title", "referenceKeys", "status", "createdAt", "titleSource").inOrder()
        db.query("SELECT title, titleSource FROM cases WHERE id = 'c1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Appointment reminder · Z Zahnarztpraxis")
            assertThat(c.getString(1)).isEqualTo("AUTO")
        }
    }

    @Test
    fun `running it again changes nothing`() {
        PamMigrations.MIGRATION_27_28.migrate(db)
        db.execSQL("UPDATE cases SET titleSource = 'USER' WHERE id = 'c1'")

        PamMigrations.MIGRATION_27_28.migrate(db)

        assertThat(columns().count { it == "titleSource" }).isEqualTo(1)
        db.query("SELECT titleSource FROM cases").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("USER")
        }
    }
}
