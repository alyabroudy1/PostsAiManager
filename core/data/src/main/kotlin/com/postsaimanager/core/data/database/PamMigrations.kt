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

    /**
     * Lets a stopped or crashed reply survive instead of vanishing (chat stop/crash UX).
     *
     * Before this, a partial reply was persisted the same as a finished one, and the
     * engine's chat session committed it into the KV cache as if the model had actually
     * said it in full — so the *next* turn silently continued from words the user
     * interrupted. `incomplete` marks such a reply so the UI can show a "Stopped" caption
     * and [com.postsaimanager.core.domain.usecase.SendChatMessageUseCase] can exclude it
     * from both the history it replays into a rebuilt prompt and — going forward — the
     * live chat session, whose own pending-reply tokens are separately rolled back via
     * `AiEngine.discardPendingReply`. Nullable-safe default `0`: every message persisted
     * before this migration finished normally, exactly like `false`.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `messages` ADD COLUMN `incomplete` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /**
     * Keeps which page each chunk came from (Phase 4.0, retrieval-grounded chat).
     *
     * Before this, [com.postsaimanager.core.domain.repository.StoredChunk] only had an
     * `ordinal` — a position in the chunking order with no relation to the document's own
     * page numbers, so a retrieved passage could never be cited as "page 3" the way a
     * person reading the letter would expect. Nullable and left null for chunks indexed
     * before this migration: they are still fully searchable, just without a page citation,
     * exactly like an OCR block scanned before layout tracking existed
     * ([MIGRATION_3_4]). They start citing pages again once their document is re-processed
     * — indexing always replaces a document's chunks wholesale
     * ([com.postsaimanager.core.domain.repository.DocumentChunkRepository.replaceChunks]).
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `pageNumber` INTEGER")
        }
    }

    /**
     * Lets an assistant reply remember which passages it was grounded on (Phase 4.3,
     * citations).
     *
     * Before this, [RetrieveChunksUseCase][com.postsaimanager.core.domain.usecase
     * .RetrieveChunksUseCase]'s results lived only as long as the turn that used them —
     * `ChatTurn.Complete.sources` existed but nothing persisted it, so a citation chip could
     * never be shown once a conversation was reloaded. `message_sources` is a child table
     * (see [com.postsaimanager.core.data.database.entity.MessageSourceEntity]'s doc comment
     * for why a table rather than a JSON column) with `CASCADE` on the owning message, so
     * deleting a conversation or a message cleans its sources up the same way `document_pages`
     * already cleans up after a deleted document. Nothing to backfill: a message persisted
     * before this migration simply has no rows here, exactly like one from a model that was
     * never grounded on retrieved passages.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `message_sources` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `messageId` TEXT NOT NULL,
                    `documentId` TEXT NOT NULL,
                    `pageNumber` INTEGER,
                    `chunkId` TEXT NOT NULL,
                    FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_sources_messageId` " +
                    "ON `message_sources` (`messageId`)",
            )
        }
    }

    /**
     * Records how much of a document extraction actually read, when its layout had to be
     * cut to fit the model's context budget (Phase 5.4).
     *
     * Before this, `AiExtractionUseCase` silently dropped the tail of a long document — the
     * model answered from whatever fit, with no record anywhere that later pages were never
     * seen at all. Nullable and left null for every document processed before this
     * migration: exactly like a document whose last extraction happened to read it whole,
     * which is the honest reading — nothing here claims a page was skipped when it is simply
     * unknown.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractionPagesRead` INTEGER")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractionTotalPages` INTEGER")
        }
    }

    /**
     * Adds a trash for documents (Phase: delete/undo/restore).
     *
     * `deletedAt` is nullable and additive: null (the default for every existing row) means
     * "not deleted"; a timestamp means the document was moved to trash at that time and is
     * hidden from every list, search and chat-retrieval path until it is restored or purged.
     * Rows and files are kept while trashed so restore is a plain field flip, not a re-import.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `deletedAt` INTEGER")
        }
    }

    /**
     * Marks a reply that ran into its token cap ("Answer was cut off") as distinct from one
     * the user stopped. Additive with default 0: every existing message reads as not cut off.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `cutOff` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Persist and display what extraction v2 reads (Phase 1, workstream E). Purely additive: every
     * new column is nullable (or defaults to false), so existing rows read as "an older extractor
     * wrote this" and nothing is rewritten.
     *
     * - `extracted_data`: the slot the value fills (`slotKey`, the identity a re-read is matched
     *   by), the model's `role` word, `origin`, the model's own `aiConfidence` next to the final
     *   `confidence`, the `evidence` text and its `bbox` (JSON). The extractor version is the
     *   existing `engineVersion`; the page is the existing `pageNumber`.
     * - `documents`: the model's type and its confidence, the extractor version, whether the title
     *   is a person's, the three suggested chat questions and the summary (JSON / text), and the
     *   title as a code with arguments while it is still an app default ("Scanned N pages").
     * - `timeline_events`: `code` and `args` (JSON), so an event is stored as data and rendered from
     *   string resources; rows without a code keep showing their stored title and description.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `slotKey` TEXT")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `role` TEXT")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `origin` TEXT")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `aiConfidence` REAL")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `evidence` TEXT")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `bbox` TEXT")

            db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractionType` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractionTypeConfidence` REAL")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractorVersion` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `isUserTitle` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `suggestedQuestions` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `summary` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `titleCode` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `titleArgs` TEXT")

            db.execSQL("ALTER TABLE `timeline_events` ADD COLUMN `code` TEXT")
            db.execSQL("ALTER TABLE `timeline_events` ADD COLUMN `args` TEXT")

            // A default title the scanner wrote ("Scanned 3 page(s)") becomes a code with its page
            // count, so it can be shown in the user's language. The stored title stays as the
            // English fallback. Only the exact default shape is converted; anything else was
            // written by the model or a person.
            db.execSQL(
                """
                UPDATE `documents`
                SET `titleCode` = 'scanned_pages',
                    `titleArgs` = '["' || CAST(`pageCount` AS TEXT) || '"]'
                WHERE `title` = 'Scanned ' || CAST(`pageCount` AS TEXT) || ' page(s)'
                """.trimIndent(),
            )
        }
    }

    /**
     * The final extraction architecture's storage (P0b). Additive: every new column is nullable or has a
     * default, and existing rows are backfilled from what they already say.
     *
     * - `extracted_data.reviewState` becomes the owner of review state. Backfill, later rule wins:
     *   confirmed -> CONFIRMED; a confirmed value that differs from the machine's (or has none) -> EDITED; deletedByUser -> IGNORED.
     *   `isConfirmed` and `deletedByUser` stay and are written in step. `alternatives` is a JSON list.
     * - `documents`: `topics` (JSON list), `familySource`, `titleSource` (a person's title -> USER, a
     *   default with a code -> DEFAULT, other real words -> MODEL), `summarySource` (an existing summary
     *   -> MODEL), `summaryCode`/`summaryArgs`, `layoutTemplate` and `enrichmentAttempts` (how many times the second stage ran
     *   without settling a summary; 0) and `enrichmentPending` (a second stage is owed; 0).
     * - `documents.extractionType` now holds a family id: the legacy type ids are rewritten and their
     *   topics filled by [LegacyTypeSql] from `LegacyTypes`, so old documents render before a re-read.
     *
     * - `entity_proposals` is dropped (the "is this you?" proposals are gone; v15 is unreleased, so the drop is part of this
     *   migration). `dismissed_entities` stays: it keeps a deleted machine-made profile from coming back.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `entity_proposals`")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `reviewState` TEXT NOT NULL DEFAULT 'UNREVIEWED'")
            db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `alternatives` TEXT")
            db.execSQL("UPDATE `extracted_data` SET `reviewState` = 'CONFIRMED' WHERE `isConfirmed` = 1")
            // A confirmation also set source = USER, so a confirmed value is EDITED only when it differs from the machine's.
            db.execSQL(
                "UPDATE `extracted_data` SET `reviewState` = 'EDITED' WHERE `source` = 'USER' AND `isConfirmed` = 1 " +
                    "AND (`machineValue` IS NULL OR `fieldValue` != `machineValue`)",
            )
            db.execSQL("UPDATE `extracted_data` SET `reviewState` = 'IGNORED' WHERE `deletedByUser` = 1")

            db.execSQL("ALTER TABLE `documents` ADD COLUMN `topics` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `familySource` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `titleSource` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `summarySource` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `summaryCode` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `summaryArgs` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `layoutTemplate` TEXT")
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `enrichmentAttempts` INTEGER NOT NULL DEFAULT 0")
            // A second stage is owed (startup recovery keys on it; a migrated document owes none).
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `enrichmentPending` INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                UPDATE `documents` SET `titleSource` = CASE
                    WHEN `isUserTitle` = 1 THEN 'USER'
                    WHEN `titleCode` IS NOT NULL THEN 'DEFAULT'
                    ELSE 'MODEL' END
                """.trimIndent(),
            )
            db.execSQL(
                "UPDATE `documents` SET `summarySource` = 'MODEL' WHERE `summary` IS NOT NULL AND trim(`summary`) != ''",
            )
            LegacyTypeSql.statements().forEach(db::execSQL)
        }
    }

    /**
     * v15 to v16 (form assist, additive; v16 is unreleased, so later form-assist tables join this migration through
     * [FormAssistSchemaSql]): the family-profile columns on `profiles`, the `profile_facts` table and the form-filling
     * conversation's `form_fills` and `form_fields` tables.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            FormAssistSchemaSql.statements().forEach(db::execSQL)
        }
    }

    /** v16 to v17: a form fill records the reading (way of reading plus OCR) it was built from, so an out-of-date fill is never resumed. */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `form_fills` ADD COLUMN `readingKey` TEXT")
        }
    }

    /**
     * v17 to v18: a document keeps the action lines its second stage wrote (`actionItems`, a JSON list of strings; NULL reads as none).
     * Additive: nothing is rewritten. Never change this migration: a build with v18 was installed (v18 means only `actionItems`).
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `documents` ADD COLUMN `actionItems` TEXT")
        }
    }

    /**
     * v18 to v19: a stored field the key information it was picked as (`importance` on `extracted_data`, the score; NULL is not key
     * information). Idempotent: some v18 databases already have the column (an earlier build put it into the 17 to 18 step), so it is
     * added only when missing.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasImportance = db.query("PRAGMA table_info(`extracted_data`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == "importance" }
            }
            if (!hasImportance) db.execSQL("ALTER TABLE `extracted_data` ADD COLUMN `importance` REAL")
        }
    }

    /**
     * v20: `documents.concernedProfileIds`, a JSON list of the profile ids the model decided the document is for or about. Null means
     * "not asked yet" (a background check fills it); `[]` means "asked, nobody". Additive and idempotent: a build that already added the
     * column is left alone. 1..19 are untouched (19 is installed on phones).
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val hasColumn = db.query("PRAGMA table_info(`documents`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == "concernedProfileIds" }
            }
            if (!hasColumn) db.execSQL("ALTER TABLE `documents` ADD COLUMN `concernedProfileIds` TEXT")
        }
    }

    /**
     * v21 (file import): `documents.sourceHash` (SHA-256 of the imported file, to notice the same file added twice) and
     * `documents.originalFilePath` (the original PDF kept privately). Both nullable, nothing is rewritten. Additive and idempotent:
     * each column is added only when missing. 1..20 are untouched (20 is installed on phones).
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = db.query("PRAGMA table_info(`documents`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toSet()
            }
            if ("sourceHash" !in existing) db.execSQL("ALTER TABLE `documents` ADD COLUMN `sourceHash` TEXT")
            if ("originalFilePath" !in existing) db.execSQL("ALTER TABLE `documents` ADD COLUMN `originalFilePath` TEXT")
        }
    }

    /**
     * v22 (household and contacts): `profiles.kind` and `profiles.householdRole` filled from `type`, the tables `contact_persons`,
     * `document_contacts` and `organisation_references`, and the move of former caseworker profiles into their organisation. See
     * [HouseholdMigration]. Additive and idempotent. 1..21 are untouched (21 is installed on phones). Renumber by changing only this
     * step's versions.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) = HouseholdMigration.apply(db)
    }

    /**
     * v23 (document memory): the table `document_notes`, the durable notes of a document ("What the assistant remembers"), gone with
     * their document. Additive and idempotent: the table and its index are created only when missing. 1..22 are untouched (22 is
     * installed on phones).
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `document_notes` (`id` TEXT NOT NULL, `documentId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, " +
                    "`sourceRef` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_notes_documentId` ON `document_notes` (`documentId`)")
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
    )
}
