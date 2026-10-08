package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The v27 step (own details and suggestions): `profiles.customDetails` and `contact_persons.customDetails` (a nullable JSON text, the
 * user's own named details) and the table `profile_suggestions` (values a letter showed for an organisation, waiting for the user).
 * Additive and idempotent: a column that is there is left alone, the table and its indices are created only when missing; every existing
 * row keeps its data and reads "no details".
 */
internal object ContactsUxMigration {

    fun apply(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "profiles", "customDetails")
        addColumnIfMissing(db, "contact_persons", "customDetails")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_suggestions` (`id` TEXT NOT NULL, `profileId` TEXT NOT NULL, `field` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, `sourceDocumentId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_suggestions_profileId` ON `profile_suggestions` (`profileId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_suggestions_profileId_field_value` " +
                "ON `profile_suggestions` (`profileId`, `field`, `value`)",
        )
    }

    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String) {
        val has = db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == column }
        }
        if (!has) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT")
    }
}
