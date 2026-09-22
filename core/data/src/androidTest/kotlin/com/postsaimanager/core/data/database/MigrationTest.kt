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

    @Test
    fun migrate4To5_preservesExistingProfilesAndLinks() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO profiles
                    (id, type, name, organization, department, street, city, postalCode,
                     country, phone, email, website, reference, notes, completionScore,
                     missingFields, avatarPath, createdAt, modifiedAt)
                VALUES
                    ('p1', 'AUTHORITY', 'Jobcenter Berlin Mitte', 'Jobcenter Berlin Mitte',
                     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0,
                     NULL, NULL, 1, 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true, PamMigrations.MIGRATION_4_5,
        )

        // A profile scanned before this migration existed is still there, with the two new
        // provenance columns simply unset — exactly what "additive" is supposed to mean.
        db.query("SELECT id, name, sourceDocumentId, sourceEntityName FROM profiles").use { cursor ->
            assertTrue("the profile was lost in migration", cursor.moveToFirst())
            assertEquals("p1", cursor.getString(0))
            assertEquals("Jobcenter Berlin Mitte", cursor.getString(1))
            assertTrue("sourceDocumentId should be NULL for a pre-existing profile", cursor.isNull(2))
            assertTrue("sourceEntityName should be NULL for a pre-existing profile", cursor.isNull(3))
        }
    }

    @Test
    fun migrate4To5_dismissedEntitiesTableWorksAndCascades() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true, PamMigrations.MIGRATION_4_5,
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO dismissed_entities (documentId, entityName, dismissedAt) " +
                "VALUES ('doc-1', 'layla', 1)",
        )

        db.execSQL("DELETE FROM documents WHERE id = 'doc-1'")

        // A dismissal outliving its document would mean a *different* document that happens
        // to reuse the same id inherits someone else's refusal.
        db.query("SELECT COUNT(*) FROM dismissed_entities").use { cursor ->
            cursor.moveToFirst()
            assertEquals("dismissals outlived their document", 0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate5To6_entityProposalsTableWorksAndCascades() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 6, true, PamMigrations.MIGRATION_5_6,
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            """
            INSERT INTO entity_proposals
                (id, documentId, entityName, entityNameKey, kind, entityRole, relation, role,
                 profileType, organization, existingProfileId, confidence, createdAt)
            VALUES
                ('prop-1', 'doc-1', 'Layla', 'layla', 'PERSON', 'MENTIONED',
                 'spouse of the recipient', 'RELATED', 'PERSON', NULL, NULL, 0.95, 1)
            """.trimIndent(),
        )

        db.query("SELECT entityName FROM entity_proposals WHERE documentId = 'doc-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Layla", cursor.getString(0))
        }

        db.execSQL("DELETE FROM documents WHERE id = 'doc-1'")

        // A proposal outliving its document would ask the user about an entity from a
        // document they can no longer even open.
        db.query("SELECT COUNT(*) FROM entity_proposals").use { cursor ->
            cursor.moveToFirst()
            assertEquals("proposals outlived their document", 0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate5To6_duplicateProposalForTheSameEntityIsRejected() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 6, true, PamMigrations.MIGRATION_5_6,
        )
        db.execSQL(
            """
            INSERT INTO entity_proposals
                (id, documentId, entityName, entityNameKey, kind, entityRole, relation, role,
                 profileType, organization, existingProfileId, confidence, createdAt)
            VALUES
                ('prop-1', 'doc-1', 'Sam', 'sam', 'PERSON', 'RECIPIENT', '', 'RECEIVER',
                 'USER_SELF', NULL, NULL, 0.9, 1)
            """.trimIndent(),
        )

        // Reprocessing the same letter must not turn one pending question into two — the
        // unique index this test pins is exactly what `EntityProposalDao.insert`'s
        // `OnConflictStrategy.IGNORE` relies on.
        val threw = runCatching {
            db.execSQL(
                """
                INSERT INTO entity_proposals
                    (id, documentId, entityName, entityNameKey, kind, entityRole, relation,
                     role, profileType, organization, existingProfileId, confidence, createdAt)
                VALUES
                    ('prop-2', 'doc-1', 'Sam', 'sam', 'PERSON', 'RECIPIENT', '', 'RECEIVER',
                     'USER_SELF', NULL, NULL, 0.9, 2)
                """.trimIndent(),
            )
        }.isFailure
        assertTrue("the unique (documentId, entityNameKey) index did not reject the duplicate", threw)

        db.query("SELECT COUNT(*) FROM entity_proposals").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migrate6To7_thinkingColumnsAreAdditiveAndNullForExistingMessages() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO conversations
                    (id, documentId, aiModelId, modelType, title, lastMessageAt,
                     messageCount, isActive, createdAt)
                VALUES ('conv-1', NULL, NULL, 'LOCAL', 'Chat', 1, 1, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, role, content, mediaType, mediaPath, toolCallId,
                     toolName, toolArgs, toolResult, isStreaming, createdAt)
                VALUES
                    ('m1', 'conv-1', 'ASSISTANT', 'The sky is blue.', 'TEXT', NULL, NULL,
                     NULL, NULL, NULL, 0, 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 7, true, PamMigrations.MIGRATION_6_7,
        )

        // A reply persisted before thinking/answer separation existed keeps its content and
        // simply has no reasoning trace — exactly like a model that never emits one.
        db.query(
            "SELECT content, thinking, thinkingDurationMs FROM messages WHERE id = 'm1'",
        ).use { cursor ->
            assertTrue("the message was lost in migration", cursor.moveToFirst())
            assertEquals("The sky is blue.", cursor.getString(0))
            assertTrue("thinking should be NULL for a pre-existing message", cursor.isNull(1))
            assertTrue(
                "thinkingDurationMs should be NULL for a pre-existing message",
                cursor.isNull(2),
            )
        }

        db.execSQL(
            "UPDATE messages SET thinking = 'Because of Rayleigh scattering.', " +
                "thinkingDurationMs = 1234 WHERE id = 'm1'",
        )
        db.query(
            "SELECT thinking, thinkingDurationMs FROM messages WHERE id = 'm1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Because of Rayleigh scattering.", cursor.getString(0))
            assertEquals(1234, cursor.getLong(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
