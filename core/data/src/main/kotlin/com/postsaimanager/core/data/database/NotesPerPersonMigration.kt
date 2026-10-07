package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The whole of the notes-per-person migration, self-contained so it can be renumbered by changing the one `Migration(from, to)` that
 * calls [apply]. `document_notes` gains `profileId` (a household person the note is about) and `documentId` becomes nullable: a note
 * belongs to a document, to a person, or to neither (a household-wide note of the all-documents chat).
 *
 * SQLite cannot make a column nullable in place, so the table is rebuilt: the new table is created, the rows are copied (`profileId`
 * null), the old table is dropped and the new one renamed. Idempotent: a table that already has `profileId` is left alone, a missing
 * table is simply created, and a half-finished earlier run (the temporary table left behind) is started over.
 */
internal object NotesPerPersonMigration {

    private const val TABLE = "document_notes"
    private const val TEMPORARY = "document_notes_v25"

    /** The table exactly as Room exports it for version 25, for [name]. */
    private fun createTable(name: String) =
        "CREATE TABLE IF NOT EXISTS `$name` (`id` TEXT NOT NULL, `documentId` TEXT, `profileId` TEXT, `text` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, " +
            "`sourceRef` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** The version-25 table and its indices, as Room exports them (the shape the schema test compares). */
    val statements: List<String> = listOf(
        createTable(TABLE),
        "CREATE INDEX IF NOT EXISTS `index_document_notes_documentId` ON `$TABLE` (`documentId`)",
        "CREATE INDEX IF NOT EXISTS `index_document_notes_profileId` ON `$TABLE` (`profileId`)",
    )

    fun apply(db: SupportSQLiteDatabase) {
        val columns = columnsOf(db, TABLE)
        if (columns.isNotEmpty() && "profileId" !in columns) {
            db.execSQL("DROP TABLE IF EXISTS `$TEMPORARY`")
            db.execSQL(createTable(TEMPORARY))
            db.execSQL(
                "INSERT INTO `$TEMPORARY` (`id`, `documentId`, `text`, `source`, `createdAt`, `updatedAt`, `pinned`, `sourceRef`) " +
                    "SELECT `id`, `documentId`, `text`, `source`, `createdAt`, `updatedAt`, `pinned`, `sourceRef` FROM `$TABLE`",
            )
            db.execSQL("DROP TABLE `$TABLE`")
            db.execSQL("ALTER TABLE `$TEMPORARY` RENAME TO `$TABLE`")
        }
        statements.forEach(db::execSQL)
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toSet()
        }
}
