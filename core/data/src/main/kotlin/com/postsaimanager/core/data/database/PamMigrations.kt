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

    val ALL = arrayOf(MIGRATION_1_2)
}
