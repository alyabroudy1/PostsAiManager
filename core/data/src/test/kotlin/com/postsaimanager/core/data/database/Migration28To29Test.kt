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
 * `MIGRATION_28_29` run for real on SQLite (Robolectric), against minimal v28 `documents` and `cases` tables: the four columns of "the user
 * can edit everything" arrive, every existing row reads as decided by the app, the rows keep their data, and a second run changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration28To29Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun openV28() {
        val context = RuntimeEnvironment.getApplication() as Context
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `documents` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `language` TEXT, PRIMARY KEY(`id`))")
                        db.execSQL(
                            "CREATE TABLE `cases` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                                "`titleSource` TEXT NOT NULL DEFAULT 'AUTO', PRIMARY KEY(`id`))",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        db.execSQL("INSERT INTO documents (id, title, language) VALUES ('d1', 'Rechnung', 'de')")
        db.execSQL("INSERT INTO cases (id, title, status) VALUES ('c1', 'Antrag', 'OPEN')")
    }

    @After
    fun close() = helper.close()

    private fun columns(table: String): List<String> = db.query("SELECT name FROM pragma_table_info('$table') ORDER BY cid").use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
    }

    private fun value(sql: String): String = db.query(sql).use { c ->
        assertThat(c.moveToFirst()).isTrue()
        c.getString(0)
    }

    @Test
    fun `the migration is the step from 28 to 29 and is registered`() {
        assertThat(PamMigrations.MIGRATION_28_29.startVersion).isEqualTo(28)
        assertThat(PamMigrations.MIGRATION_28_29.endVersion).isEqualTo(29)
        assertThat(PamMigrations.ALL.toList()).contains(PamMigrations.MIGRATION_28_29)
        assertThat(PamMigrations.ALL.map { it.endVersion }.max()).isAtLeast(29)
    }

    @Test
    fun `the columns arrive, the rows are kept and every source is the app's`() {
        PamMigrations.MIGRATION_28_29.migrate(db)

        assertThat(columns("documents")).containsExactly("id", "title", "language", "concernedSource", "languageSource", "caseLinkSource").inOrder()
        assertThat(columns("cases")).containsExactly("id", "title", "status", "titleSource", "statusSource").inOrder()
        assertThat(value("SELECT title FROM documents WHERE id = 'd1'")).isEqualTo("Rechnung")
        assertThat(value("SELECT concernedSource FROM documents")).isEqualTo("MODEL")
        assertThat(value("SELECT languageSource FROM documents")).isEqualTo("MODEL")
        assertThat(value("SELECT caseLinkSource FROM documents")).isEqualTo("AUTO")
        assertThat(value("SELECT statusSource FROM cases")).isEqualTo("AUTO")
    }

    @Test
    fun `running it again changes nothing`() {
        PamMigrations.MIGRATION_28_29.migrate(db)
        db.execSQL("UPDATE documents SET concernedSource = 'USER', languageSource = 'USER', caseLinkSource = 'USER'")
        db.execSQL("UPDATE cases SET statusSource = 'USER'")

        PamMigrations.MIGRATION_28_29.migrate(db)

        assertThat(columns("documents").count { it == "concernedSource" }).isEqualTo(1)
        assertThat(columns("cases").count { it == "statusSource" }).isEqualTo(1)
        assertThat(value("SELECT concernedSource FROM documents")).isEqualTo("USER")
        assertThat(value("SELECT languageSource FROM documents")).isEqualTo("USER")
        assertThat(value("SELECT caseLinkSource FROM documents")).isEqualTo("USER")
        assertThat(value("SELECT statusSource FROM cases")).isEqualTo("USER")
    }
}
