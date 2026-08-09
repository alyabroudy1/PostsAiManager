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

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
