package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The v29 step (the user can edit everything the AI fills in): who decided each value that had no record of it. Additive and idempotent
 * (a column that is there is left alone, so a repeated run changes nothing); every existing row reads as decided by the app, because no
 * edit of these values was possible before, so none could have been the user's.
 *
 * - `documents.concernedSource` (`MODEL`): who decided the household people a letter is for.
 * - `documents.languageSource` (`MODEL`): who decided the letter's language.
 * - `documents.caseLinkSource` (`AUTO`): who chose the matter the letter belongs to.
 * - `cases.statusSource` (`AUTO`): who decided a matter's status.
 *
 * Nothing else changes: an action's source lives inside the `documents.actionItems` JSON, and a note's source column already exists.
 */
internal object UserEditsMigration {

    private val columns: List<Triple<String, String, String>> = listOf(
        Triple("documents", "concernedSource", "'MODEL'"),
        Triple("documents", "languageSource", "'MODEL'"),
        Triple("documents", "caseLinkSource", "'AUTO'"),
        Triple("cases", "statusSource", "'AUTO'"),
    )

    fun apply(db: SupportSQLiteDatabase) {
        columns.forEach { (table, column, default) ->
            if (!hasColumn(db, table, column)) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT NOT NULL DEFAULT $default")
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == column }
        }
}
