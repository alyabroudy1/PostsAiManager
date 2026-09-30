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

    @Test
    fun migrate7To8_incompleteColumnIsAdditiveAndFalseForExistingMessages() {
        helper.createDatabase(TEST_DB, 7).apply {
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
                     toolName, toolArgs, toolResult, isStreaming, createdAt, thinking,
                     thinkingDurationMs)
                VALUES
                    ('m1', 'conv-1', 'ASSISTANT', 'The sky is blue.', 'TEXT', NULL, NULL,
                     NULL, NULL, NULL, 0, 1, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 8, true, PamMigrations.MIGRATION_7_8,
        )

        // A reply persisted before Stop-mid-stream existed finished normally, by
        // definition — it must read as `incomplete = 0`, not just "not null".
        db.query("SELECT content, incomplete FROM messages WHERE id = 'm1'").use { cursor ->
            assertTrue("the message was lost in migration", cursor.moveToFirst())
            assertEquals("The sky is blue.", cursor.getString(0))
            assertEquals("a pre-existing message must not read as incomplete", 0, cursor.getInt(1))
        }

        db.execSQL(
            """
            INSERT INTO messages
                (id, conversationId, role, content, mediaType, mediaPath, toolCallId,
                 toolName, toolArgs, toolResult, isStreaming, createdAt, thinking,
                 thinkingDurationMs, incomplete)
            VALUES
                ('m2', 'conv-1', 'ASSISTANT', 'The sky is', 'TEXT', NULL, NULL, NULL, NULL,
                 NULL, 0, 2, NULL, NULL, 1)
            """.trimIndent(),
        )
        db.query("SELECT incomplete FROM messages WHERE id = 'm2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migrate8To9_pageNumberColumnIsAdditiveAndNullForExistingChunks() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 2, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO document_chunks
                    (id, documentId, ordinal, text, embedding, embeddingModelId, createdAt)
                VALUES ('c1', 'doc-1', 0, 'Sehr geehrte Damen und Herren', NULL, 'e5-small', 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 9, true, PamMigrations.MIGRATION_8_9,
        )

        // A chunk indexed before page-aware chunking existed keeps its text and simply has
        // no page number — exactly like OCR blocks scanned before layout tracking existed.
        db.query("SELECT text, pageNumber FROM document_chunks WHERE id = 'c1'").use { cursor ->
            assertTrue("the chunk was lost in migration", cursor.moveToFirst())
            assertEquals("Sehr geehrte Damen und Herren", cursor.getString(0))
            assertTrue("pageNumber should be NULL for a pre-existing chunk", cursor.isNull(1))
        }

        db.execSQL(
            """
            INSERT INTO document_chunks
                (id, documentId, ordinal, text, embedding, embeddingModelId, createdAt, pageNumber)
            VALUES ('c2', 'doc-1', 1, 'Zweite Seite', NULL, 'e5-small', 2, 2)
            """.trimIndent(),
        )
        db.query("SELECT pageNumber FROM document_chunks WHERE id = 'c2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test
    fun migrate9To10_messageSourcesTableWorksAndCascades() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 2, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO conversations
                    (id, documentId, aiModelId, modelType, title, lastMessageAt,
                     messageCount, isActive, createdAt)
                VALUES ('conv-1', 'doc-1', NULL, 'LOCAL', 'Chat', 1, 1, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, role, content, mediaType, mediaPath, toolCallId,
                     toolName, toolArgs, toolResult, isStreaming, createdAt, thinking,
                     thinkingDurationMs, incomplete)
                VALUES
                    ('m1', 'conv-1', 'ASSISTANT', 'The deadline is in two weeks [p.2].', 'TEXT',
                     NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, 0)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 10, true, PamMigrations.MIGRATION_9_10,
        )

        // A message persisted before citations existed keeps its content and simply has no
        // sources — exactly like an assistant reply generated outside retrieval mode.
        db.query("SELECT COUNT(*) FROM message_sources WHERE messageId = 'm1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("a pre-existing message must not gain sources", 0, cursor.getInt(0))
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO message_sources (messageId, documentId, pageNumber, chunkId) " +
                "VALUES ('m1', 'doc-1', 2, 'chunk-1')",
        )
        db.query("SELECT documentId, pageNumber, chunkId FROM message_sources WHERE messageId = 'm1'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("doc-1", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals("chunk-1", cursor.getString(2))
            }

        db.execSQL("DELETE FROM messages WHERE id = 'm1'")

        // A source outliving its message would render as a citation on nothing.
        db.query("SELECT COUNT(*) FROM message_sources").use { cursor ->
            cursor.moveToFirst()
            assertEquals("sources outlived their message", 0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate10To11_extractionCoverageColumnsAreAdditiveAndNullForExistingDocuments() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 3, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 11, true, PamMigrations.MIGRATION_10_11,
        )

        // A document extracted before this migration existed simply has no truncation
        // notice — exactly like one whose last extraction happened to read it whole.
        db.query(
            "SELECT title, extractionPagesRead, extractionTotalPages FROM documents WHERE id = 'doc-1'",
        ).use { cursor ->
            assertTrue("the document was lost in migration", cursor.moveToFirst())
            assertEquals("Bescheid", cursor.getString(0))
            assertTrue("extractionPagesRead should be NULL for a pre-existing document", cursor.isNull(1))
            assertTrue("extractionTotalPages should be NULL for a pre-existing document", cursor.isNull(2))
        }

        db.execSQL(
            "UPDATE documents SET extractionPagesRead = 1, extractionTotalPages = 3 WHERE id = 'doc-1'",
        )
        db.query(
            "SELECT extractionPagesRead, extractionTotalPages FROM documents WHERE id = 'doc-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(3, cursor.getInt(1))
        }
    }

    @Test
    fun migrate11To12_deletedAtColumnIsAdditiveAndNullForExistingDocuments() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Bescheid', 'EXTRACTED', 'CAMERA', 3, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 12, true, PamMigrations.MIGRATION_11_12,
        )

        // A document that existed before trash was added is simply not deleted.
        db.query("SELECT deletedAt FROM documents WHERE id = 'doc-1'").use { cursor ->
            assertTrue("the document was lost in migration", cursor.moveToFirst())
            assertTrue("deletedAt should be NULL for a pre-existing document", cursor.isNull(0))
        }

        db.execSQL("UPDATE documents SET deletedAt = 1000 WHERE id = 'doc-1'")
        db.query("SELECT deletedAt FROM documents WHERE id = 'doc-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1000, cursor.getLong(0))
        }
    }

    @Test
    fun migrate12To13_cutOffColumnIsAdditiveAndFalseForExistingMessages() {
        helper.createDatabase(TEST_DB, 12).apply {
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
                     toolName, toolArgs, toolResult, isStreaming, createdAt, thinking,
                     thinkingDurationMs, incomplete)
                VALUES ('m1', 'conv-1', 'ASSISTANT', 'Stopped text', 'TEXT', NULL, NULL, NULL,
                        NULL, NULL, 0, 1, NULL, NULL, 1)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 13, true, PamMigrations.MIGRATION_12_13,
        )

        db.query("SELECT incomplete, cutOff FROM messages WHERE id = 'm1'").use { cursor ->
            assertTrue("the message was lost in migration", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("a pre-existing message must not read as cut off", 0, cursor.getInt(1))
        }
    }

    @Test
    fun migrate13To14_addsTheExtractionColumnsAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite,
                     createdAt, modifiedAt, syncStatus)
                VALUES ('doc-1', 'Scanned 3 page(s)', 'EXTRACTED', 'CAMERA', 3, 0, 1, 1, 'LOCAL'),
                       ('doc-2', 'Nordlicht Mobilfunk: Zahlungserinnerung', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO extracted_data
                    (id, documentId, fieldName, fieldValue, fieldType, confidence, pageNumber,
                     isConfirmed, source, machineValue, machineConfidence, deletedByUser,
                     hasUnreviewedMachineChange, engineVersion, updatedAt)
                VALUES ('f1', 'doc-2', 'Amount', '64,98 EUR', 'OTHER', 0.9, 1,
                        1, 'USER', '64,98 EUR', 0.9, 0, 0, 'extraction-v2-1', 5)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO timeline_events
                    (id, documentId, eventType, title, description, data, referenceId,
                     referenceType, createdAt)
                VALUES ('t1', 'doc-1', 'TEXT_EXTRACTED', 'Text extracted from 3 page(s)',
                        'Average confidence: 91%', NULL, NULL, NULL, 2)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 14, true, PamMigrations.MIGRATION_13_14,
        )

        // Extracted values keep everything; the new columns read as "an older extractor wrote it".
        db.query(
            "SELECT fieldValue, source, isConfirmed, engineVersion, slotKey, role, origin, " +
                "aiConfidence, evidence, bbox FROM extracted_data WHERE id = 'f1'",
        ).use { c ->
            assertTrue("the field was lost in migration", c.moveToFirst())
            assertEquals("64,98 EUR", c.getString(0))
            assertEquals("USER", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals("extraction-v2-1", c.getString(3))
            for (i in 4..9) assertTrue("column $i should be NULL", c.isNull(i))
        }

        // Documents: everything survives, the flags default to "not a person's title", and the
        // scanner's default title becomes a code with its page count. A real title is left alone.
        db.query(
            "SELECT title, isUserTitle, extractionType, extractionTypeConfidence, extractorVersion, " +
                "suggestedQuestions, summary, titleCode, titleArgs FROM documents ORDER BY id",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Scanned 3 page(s)", c.getString(0))
            assertEquals(0, c.getInt(1))
            for (i in 2..6) assertTrue("column $i should be NULL", c.isNull(i))
            assertEquals("scanned_pages", c.getString(7))
            assertEquals("[\"3\"]", c.getString(8))
            assertTrue(c.moveToNext())
            assertEquals("Nordlicht Mobilfunk: Zahlungserinnerung", c.getString(0))
            assertTrue(c.isNull(7))
            assertTrue(c.isNull(8))
        }

        // Old events keep their sentences and simply have no code.
        db.query("SELECT title, description, code, args FROM timeline_events WHERE id = 't1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Text extracted from 3 page(s)", c.getString(0))
            assertEquals("Average confidence: 91%", c.getString(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
        }

        // The new columns are writable.
        db.execSQL("UPDATE extracted_data SET slotKey = 'total', aiConfidence = 0.7 WHERE id = 'f1'")
        db.query("SELECT slotKey, aiConfidence FROM extracted_data WHERE id = 'f1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("total", c.getString(0))
            assertEquals(0.7f, c.getFloat(1), 0.0001f)
        }
    }

    /**
     * v14 rows covering the four review-state backfill cases, the title and summary sources, and every
     * legacy type id. Needs a device (P4 runs it); `LegacyTypeSqlTest` covers the SQL builder on the JVM.
     */
    @Test
    fun migrate14To15_backfillsReviewStateSourcesAndTheLegacyTypeMapping() {
        helper.createDatabase(TEST_DB, 14).apply {
            val legacy = listOf(
                "bill", "reminder_dunning", "authority_tax", "health", "insurance_contract",
                "school", "receipt", "info_no_action", "other",
            )
            legacy.forEachIndexed { i, type ->
                execSQL(
                    """
                    INSERT INTO documents
                        (id, title, status, sourceType, pageCount, isFavorite, createdAt, modifiedAt, syncStatus,
                         extractionType, isUserTitle, titleCode, summary)
                    VALUES ('legacy-$i', 'Real words $i', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL',
                            '$type', 0, NULL, ${if (i == 0) "'A summary'" else "NULL"})
                    """.trimIndent(),
                )
            }
            execSQL(
                """
                INSERT INTO documents
                    (id, title, status, sourceType, pageCount, isFavorite, createdAt, modifiedAt, syncStatus,
                     extractionType, isUserTitle, titleCode)
                VALUES ('family', 'Scanned 2 page(s)', 'EXTRACTED', 'CAMERA', 2, 0, 1, 1, 'LOCAL',
                        'receipt', 0, 'scanned_pages'),
                       ('mine', 'My own title', 'EXTRACTED', 'CAMERA', 1, 0, 1, 1, 'LOCAL', NULL, 1, NULL),
                       ('untyped', 'Untyped', 'NEW', 'CAMERA', 1, 0, 1, 1, 'LOCAL', NULL, 0, NULL)
                """.trimIndent(),
            )
            fun field(id: String, confirmed: Int, source: String, deleted: Int) = execSQL(
                """
                INSERT INTO extracted_data
                    (id, documentId, fieldName, fieldValue, fieldType, confidence, pageNumber,
                     isConfirmed, source, machineValue, machineConfidence, deletedByUser,
                     hasUnreviewedMachineChange, updatedAt)
                VALUES ('$id', 'family', '$id', 'v', 'OTHER', 0.9, 1, $confirmed, '$source', 'v', 0.9, $deleted, 0, 1)
                """.trimIndent(),
            )
            field("untouched", 0, "MACHINE", 0)
            field("confirmed", 1, "MACHINE", 0)
            field("edited", 1, "USER", 0)
            field("ignored", 0, "MACHINE", 1)
            field("ignored-edited", 1, "USER", 1)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, PamMigrations.MIGRATION_14_15)

        fun reviewState(id: String) =
            db.query("SELECT reviewState, alternatives FROM extracted_data WHERE id = '$id'").use { c ->
                assertTrue("the field $id was lost in migration", c.moveToFirst())
                assertTrue("alternatives start empty", c.isNull(1))
                c.getString(0)
            }
        assertEquals("UNREVIEWED", reviewState("untouched"))
        assertEquals("CONFIRMED", reviewState("confirmed"))
        assertEquals("EDITED", reviewState("edited"))
        assertEquals("IGNORED", reviewState("ignored"))
        assertEquals("a tombstone wins over an edit", "IGNORED", reviewState("ignored-edited"))

        fun document(id: String): List<String?> =
            db.query(
                "SELECT extractionType, topics, familySource, titleSource, summarySource FROM documents WHERE id = '$id'",
            ).use { c ->
                assertTrue("the document $id was lost in migration", c.moveToFirst())
                (0..4).map { if (c.isNull(it)) null else c.getString(it) }
            }
        val expected = mapOf(
            "legacy-0" to listOf("invoice_bill", null),
            "legacy-1" to listOf("invoice_bill", null),
            "legacy-2" to listOf("official_letter", "[\"government\"]"),
            "legacy-3" to listOf("medical", "[\"health\"]"),
            "legacy-4" to listOf("contract_policy", "[\"insurance\"]"),
            "legacy-5" to listOf("official_letter", "[\"school_education\"]"),
            "legacy-6" to listOf("receipt", null),
            "legacy-7" to listOf("official_letter", null),
            "legacy-8" to listOf("free_form", null),
        )
        for ((id, typeAndTopics) in expected) {
            val row = document(id)
            assertEquals("family of $id", typeAndTopics[0], row[0])
            assertEquals("topics of $id", typeAndTopics[1], row[1])
            assertEquals("MODEL", row[2])
            assertEquals("real words are the model's title", "MODEL", row[3])
        }
        assertEquals("MODEL", document("legacy-0")[4])
        assertEquals(null, document("legacy-1")[4])

        assertEquals("DEFAULT", document("family")[3])
        assertEquals("a person's title", "USER", document("mine")[3])
        assertEquals("a document with no type has no family source", null, document("untyped")[2])
        assertEquals(null, document("untyped")[0])

        // The new columns are writable.
        db.execSQL("UPDATE documents SET layoutTemplate = 'din5008_b', summaryCode = 'template', summaryArgs = '[]' WHERE id = 'family'")
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
