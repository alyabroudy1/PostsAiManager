package com.postsaimanager.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests.
 *
 * These exist because the database previously used `fallbackToDestructiveMigration()` —
 * **every schema change silently wiped every user document**. The constraint had already
 * pushed two features (the installed-model index, then document chunks) out of Room purely
 * to avoid triggering it.
 *
 * The assertion that matters is not "the migration runs" but "the user's data is still
 * there afterwards".
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PamDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesExistingDocuments() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, documentType, language, sourceType, thumbnailPath,
                     pageCount, isFavorite, createdAt, modifiedAt, syncStatus)
                VALUES
                    ('doc-1', 'Bescheid vom Jobcenter', 'EXTRACTED', 'OFFICIAL_LETTER', 'de',
                     'CAMERA', NULL, 2, 1, 1700000000000, 1700000000000, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, PamMigrations.MIGRATION_1_2,
        )

        // The whole point: a real document written under v1 is still readable under v2.
        db.query("SELECT id, title, isFavorite FROM documents").use { cursor ->
            assertTrue("the document was lost in migration", cursor.moveToFirst())
            assertEquals("doc-1", cursor.getString(0))
            assertEquals("Bescheid vom Jobcenter", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("unexpected extra rows", 1, cursor.count)
        }
    }

    @Test
    fun migrate1To2_addsAUsableChunkTable() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Rechnung', 'NEW', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, PamMigrations.MIGRATION_1_2,
        )

        db.execSQL(
            """
            INSERT INTO document_chunks
                (id, documentId, ordinal, text, embedding, embeddingModelId, createdAt)
            VALUES ('c1', 'doc-1', 0, 'Sehr geehrte Damen und Herren', NULL, 'e5-small', 1)
            """.trimIndent(),
        )

        db.query("SELECT text FROM document_chunks WHERE documentId = 'doc-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Sehr geehrte Damen und Herren", cursor.getString(0))
        }
    }

    @Test
    fun migrate1To2_chunksCascadeWhenTheirDocumentIsDeleted() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Rechnung', 'NEW', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, PamMigrations.MIGRATION_1_2,
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            """
            INSERT INTO document_chunks
                (id, documentId, ordinal, text, embedding, embeddingModelId, createdAt)
            VALUES ('c1', 'doc-1', 0, 'text', NULL, 'e5-small', 1)
            """.trimIndent(),
        )

        db.execSQL("DELETE FROM documents WHERE id = 'doc-1'")

        // Orphaned embeddings would otherwise be returned by retrieval for a document the
        // user has deleted — a privacy problem, not just untidy data.
        db.query("SELECT COUNT(*) FROM document_chunks").use { cursor ->
            cursor.moveToFirst()
            assertEquals("chunks outlived their document", 0, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
