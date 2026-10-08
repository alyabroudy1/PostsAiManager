package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The v28 step: `cases.titleSource` (`AUTO` or `USER`), who wrote a matter's title. Additive and idempotent (a column that is there is
 * left alone). Every existing row is `AUTO`: no record of a rename was ever kept, so none can be told from a generated title, and a
 * generated title follows the letters (a matter the user renamed after this step stays as written).
 */
internal object CaseTitleSourceMigration {

    fun apply(db: SupportSQLiteDatabase) {
        val has = db.query("PRAGMA table_info(`cases`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == "titleSource" }
        }
        if (!has) db.execSQL("ALTER TABLE `cases` ADD COLUMN `titleSource` TEXT NOT NULL DEFAULT 'AUTO'")
    }
}
