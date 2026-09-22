package com.postsaimanager.core.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * `fallbackToDestructiveMigration()` was removed alongside the first of these. It meant
 * **every schema change silently wiped every user document** — the app had no way to
 * evolve without data loss, and the constraint had already forced two design decisions
 * (the installed-model index, and this table) away from Room purely to avoid triggering it.
 */
object PamMigrations {

    /** Adds `document_chunks` for semantic retrieval (Phase 7.7). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `document_chunks` (
                    `id` TEXT NOT NULL,
                    `documentId` TEXT NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `embedding` BLOB,
                    `embeddingModelId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_document_chunks_documentId` " +
                    "ON `document_chunks` (`documentId`)",
            )
        }
    }

    /**
     * Adds provenance to extracted fields, and the history behind them (Phase 7.14).
     *
     * Before this, reprocessing a document ran an unconditional
     * `DELETE FROM extracted_data` and re-inserted — destroying every correction and every
     * manually added field, with no record that they had existed. The columns below are
     * what let a merge tell a machine guess from a person's decision.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `extracted_data` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MACHINE'",
            )
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `machineValue` TEXT")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `machineConfidence` REAL")
            db.execSQL(
                "ALTER TABLE `extracted_data` ADD COLUMN `deletedByUser` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `extracted_data` " +
                    "ADD COLUMN `hasUnreviewedMachineChange` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `engineVersion` TEXT")
            db.execSQL(
                "ALTER TABLE `extracted_data` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
            )

            // Existing rows were all machine-produced, and every one is also the last thing
            // the extractor said — so machineValue seeds from the current value.
            db.execSQL("UPDATE `extracted_data` SET `machineValue` = `fieldValue`")
            db.execSQL("UPDATE `extracted_data` SET `machineConfidence` = `confidence`")

            // A confirmed field is one a person looked at and accepted. Reading that as
            // USER is what stops the very next reprocess from overwriting the decisions
            // users have already made in the installed app.
            db.execSQL("UPDATE `extracted_data` SET `source` = 'USER' WHERE `isConfirmed` = 1")

            // The new unique index cannot be created while duplicate slots exist, and they
            // can: nothing previously stopped one extraction run producing two fields with
            // the same name. Keep the most decided row per slot — confirmed first, then
            // most confident. A correlated subquery rather than a window function, because
            // API 26 ships SQLite 3.18 and ROW_NUMBER arrived in 3.25.
            db.execSQL(
                """
                DELETE FROM `extracted_data` WHERE `rowid` NOT IN (
                    SELECT (
                        SELECT d2.`rowid` FROM `extracted_data` d2
                        WHERE d2.`documentId` = d.`documentId`
                          AND d2.`fieldName` = d.`fieldName`
                        ORDER BY d2.`isConfirmed` DESC, d2.`confidence` DESC
                        LIMIT 1
                    )
                    FROM `extracted_data` d
                )
                """.trimIndent(),
            )

            db.execSQL("DROP INDEX IF EXISTS `index_extracted_data_documentId`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_extracted_data_documentId_fieldName` " +
                    "ON `extracted_data` (`documentId`, `fieldName`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `field_revisions` (
                    `id` TEXT NOT NULL,
                    `documentId` TEXT NOT NULL,
                    `fieldName` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `confidence` REAL,
                    `engineVersion` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_field_revisions_documentId_fieldName_createdAt` " +
                    "ON `field_revisions` (`documentId`, `fieldName`, `createdAt`)",
            )
        }
    }

    /**
     * Keeps where each OCR block was on the page (Phase 7.14.7).
     *
     * ML Kit returns a bounding box per block and the pipeline discarded it, flattening a
     * laid-out page into one string. A German letter puts the recipient left and the
     * reference block right, on the same lines; read as a single run of text they
     * interleave, which is how a sender organisation came to be recorded as
     * "563,00 Euro. Die Anpassung erfolgt automatisch".
     *
     * Nullable, and left null for pages scanned before this: their text is still there, and
     * re-processing the document repopulates the layout.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `document_pages` ADD COLUMN `ocrBlocks` TEXT")
        }
    }

    /**
     * Lets recognised entities become profiles and links, and lets a "no" stick (Phase 7.14.11).
     *
     * `sourceDocumentId`/`sourceEntityName` record which document and entity a profile was
     * machine-created from. Without them, deleting a profile the AI created has no way to stop
     * the next reprocess of that same document from creating it right back — the same
     * "argues with the user every run" failure `extracted_data.deletedByUser` already fixed
     * for fields. `dismissed_entities` is the equivalent tombstone for a proposal the user
     * declined outright, before any profile ever existed to delete.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `sourceDocumentId` TEXT")
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `sourceEntityName` TEXT")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dismissed_entities` (
                    `documentId` TEXT NOT NULL,
                    `entityName` TEXT NOT NULL,
                    `dismissedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`documentId`, `entityName`),
                    FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dismissed_entities_documentId` " +
                    "ON `dismissed_entities` (`documentId`)",
            )
        }
    }

    /**
     * Lets a Propose decision reach the user instead of being counted in a log line and
     * dropped (Phase 7.14.11b).
     *
     * Before this, [EntityLinkingUseCase.Action.Propose] results were collected into
     * `EntityProfileLinker.Outcome.proposals`, logged as a count, and discarded when
     * `processDocument` returned — so a spouse mentioned in a letter, or the letter's own
     * recipient, was found and then silently forgotten. `entity_proposals` is what
     * `EntityProfileLinker` now writes those decisions to, so they survive to be shown on the
     * document's detail screen and stay there until accepted or dismissed. See
     * `EntityProposalEntity`'s doc comment for why the id is generated rather than the natural
     * key, and `EntityProposalDao.insert` for how that keeps reprocessing from duplicating a
     * still-pending proposal.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `entity_proposals` (
                    `id` TEXT NOT NULL,
                    `documentId` TEXT NOT NULL,
                    `entityName` TEXT NOT NULL,
                    `entityNameKey` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `entityRole` TEXT NOT NULL,
                    `relation` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `profileType` TEXT NOT NULL,
                    `organization` TEXT,
                    `existingProfileId` TEXT,
                    `confidence` REAL NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_entity_proposals_documentId_entityNameKey` " +
                    "ON `entity_proposals` (`documentId`, `entityNameKey`)",
            )
        }
    }

    /**
     * Separates a message's answer from its reasoning trace (chat thinking/reasoning UI).
     *
     * Before this, a `<think>…</think>` block from a reasoning model (Qwen3/Qwen3.5,
     * DeepSeek) had nowhere to go but `content` — mixed in with the answer, sent back into
     * every future prompt, and rendered inline with no way to collapse it. `thinking` is
     * nullable and additive: a message persisted before this migration simply has no
     * reasoning trace, exactly like a model that never emits one.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `thinking` TEXT")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `thinkingDurationMs` INTEGER")
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}
