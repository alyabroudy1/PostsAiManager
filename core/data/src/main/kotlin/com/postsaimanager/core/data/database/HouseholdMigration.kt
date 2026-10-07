package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.postsaimanager.core.domain.document.normaliseEntityName

/**
 * The whole of the household and contacts migration (additive and idempotent), self-contained so it can be renumbered by changing
 * the one `Migration(from, to)` that calls [apply].
 *
 * 1. `profiles.kind` and `profiles.householdRole`, filled once from the old `type` (AUTHORITY is ORGANISATION; PERSON is PERSON;
 *    FAMILY_MEMBER is PERSON and MEMBER; USER_SELF is PERSON and SELF). `type` stays and stays readable for one version.
 * 2. The tables `contact_persons`, `document_contacts` and `organisation_references`.
 * 3. The data move ([moveCaseworkers]): a plain person who only ever was a letter's contact person becomes a contact of the one
 *    organisation profile with exactly the same name; everything less clear stays a profile.
 *
 * A column that is already there is not added again and not refilled, so a repeated run changes nothing.
 */
internal object HouseholdMigration {

    private val createTables: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `contact_persons` (`id` TEXT NOT NULL, `organisationId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`title` TEXT, `department` TEXT, `phone` TEXT, `email` TEXT, `room` TEXT, `firstSeen` INTEGER NOT NULL, " +
            "`lastSeen` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`organisationId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_contact_persons_organisationId` ON `contact_persons` (`organisationId`)",
        "CREATE TABLE IF NOT EXISTS `document_contacts` (`documentId` TEXT NOT NULL, `contactId` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`documentId`, `contactId`), " +
            "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`contactId`) REFERENCES `contact_persons`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_document_contacts_documentId` ON `document_contacts` (`documentId`)",
        "CREATE INDEX IF NOT EXISTS `index_document_contacts_contactId` ON `document_contacts` (`contactId`)",
        "CREATE TABLE IF NOT EXISTS `organisation_references` (`id` TEXT NOT NULL, `organisationId` TEXT NOT NULL, " +
            "`profileId` TEXT NOT NULL, `label` TEXT NOT NULL, `value` TEXT NOT NULL, `sourceDocumentId` TEXT, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`organisationId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_organisation_references_organisationId` ON `organisation_references` (`organisationId`)",
        "CREATE INDEX IF NOT EXISTS `index_organisation_references_profileId` ON `organisation_references` (`profileId`)",
    )

    /** A person profile that may become a contact: what is needed to build the contact and the tombstones. */
    private class Candidate(
        val id: String,
        val name: String,
        val organisationId: String,
        val department: String?,
        val phone: String?,
        val email: String?,
        val createdAt: Long,
        val sourceDocumentId: String?,
        val sourceEntityName: String?,
    )

    fun apply(db: SupportSQLiteDatabase) {
        val columns = columnNames(db, "profiles")
        var filled = false
        if ("kind" !in columns) {
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'PERSON'")
            filled = true
        }
        if ("householdRole" !in columns) {
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `householdRole` TEXT")
            filled = true
        }
        if (filled) {
            db.execSQL(
                "UPDATE `profiles` SET `kind` = CASE `type` WHEN 'AUTHORITY' THEN 'ORGANISATION' ELSE 'PERSON' END, " +
                    "`householdRole` = CASE `type` WHEN 'USER_SELF' THEN 'SELF' WHEN 'FAMILY_MEMBER' THEN 'MEMBER' ELSE NULL END",
            )
        }
        createTables.forEach(db::execSQL)
        moveCaseworkers(db, System.currentTimeMillis())
    }

    /**
     * Moves a former caseworker profile into the organisation it names. All of these must hold, otherwise the profile is left as
     * it is (the user can still move it by hand later):
     * - a person outside the household with an `organization` value and no data a contact cannot hold (address, website, notes,
     *   reference, birth date, avatar, relationship, sensitive flag, saved details);
     * - at least one document link, and every link is `CASE_WORKER`;
     * - exactly one organisation profile whose name equals that value (trimmed, otherwise exact). Two such profiles (two offices)
     *   or none: ambiguous, left alone.
     *
     * The contact keeps the profile's id; the seen-range is the first and the last link. Its document links are copied to
     * `document_contacts`; the profile and its links are removed, and every document it was linked to (and its source document)
     * gets a tombstone so a later reading does not create the profile again.
     */
    private fun moveCaseworkers(db: SupportSQLiteDatabase, now: Long) {
        val candidates = candidates(db)
        for (c in candidates) {
            val links = db.query(
                "SELECT `documentId`, `createdAt` FROM `document_profile_links` WHERE `profileId` = ?", arrayOf(c.id),
            ).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getLong(1) else null }.toList()
            }
            val firstSeen = links.minOfOrNull { it.second } ?: c.createdAt
            val lastSeen = links.maxOfOrNull { it.second } ?: c.createdAt

            db.execSQL(
                "INSERT OR IGNORE INTO `contact_persons` (`id`, `organisationId`, `name`, `title`, `department`, `phone`, `email`, " +
                    "`room`, `firstSeen`, `lastSeen`, `active`) VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, ?, ?, 1)",
                arrayOf<Any?>(c.id, c.organisationId, c.name, c.department, c.phone, c.email, firstSeen, lastSeen),
            )
            links.forEach { (documentId, createdAt) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `document_contacts` (`documentId`, `contactId`, `createdAt`) VALUES (?, ?, ?)",
                    arrayOf<Any?>(documentId, c.id, createdAt),
                )
            }

            // Tombstones: a document's reading names the entity as the profile's own name, except the document the profile
            // came from, which recorded the exact key it was created under.
            val tombstones = links.associate { (documentId, _) -> documentId to normaliseEntityName(c.name) }.toMutableMap()
            if (c.sourceDocumentId != null && c.sourceEntityName != null && documentExists(db, c.sourceDocumentId)) {
                tombstones[c.sourceDocumentId] = c.sourceEntityName
            }
            tombstones.forEach { (documentId, entityName) ->
                db.execSQL(
                    "INSERT OR REPLACE INTO `dismissed_entities` (`documentId`, `entityName`, `dismissedAt`) VALUES (?, ?, ?)",
                    arrayOf<Any?>(documentId, entityName, now),
                )
            }

            db.execSQL("DELETE FROM `document_profile_links` WHERE `profileId` = ?", arrayOf(c.id))
            db.execSQL("DELETE FROM `profiles` WHERE `id` = ?", arrayOf(c.id))
        }
    }

    private fun candidates(db: SupportSQLiteDatabase): List<Candidate> {
        val blank = { column: String -> "coalesce(trim(p.`$column`), '') = ''" }
        val bare = listOf("street", "city", "postalCode", "country", "website", "reference", "notes", "avatarPath", "birthDate", "relationship")
            .joinToString(" AND ", transform = blank)
        val sql = """
            SELECT p.`id`, p.`name`, o.`id`, p.`department`, p.`phone`, p.`email`, p.`createdAt`, p.`sourceDocumentId`, p.`sourceEntityName`
            FROM `profiles` p
            JOIN `profiles` o ON o.`kind` = 'ORGANISATION' AND trim(o.`name`) = trim(p.`organization`)
            WHERE p.`kind` = 'PERSON' AND p.`householdRole` IS NULL
              AND coalesce(trim(p.`organization`), '') != ''
              AND p.`sensitive` = 0
              AND $bare
              AND EXISTS (SELECT 1 FROM `document_profile_links` l WHERE l.`profileId` = p.`id`)
              AND NOT EXISTS (SELECT 1 FROM `document_profile_links` l WHERE l.`profileId` = p.`id` AND l.`role` != 'CASE_WORKER')
              AND NOT EXISTS (SELECT 1 FROM `profile_facts` f WHERE f.`profileId` = p.`id`)
              AND (SELECT COUNT(*) FROM `profiles` o2 WHERE o2.`kind` = 'ORGANISATION' AND trim(o2.`name`) = trim(p.`organization`)) = 1
        """.trimIndent()
        return db.query(sql).use { c ->
            generateSequence {
                if (c.moveToNext()) {
                    Candidate(
                        id = c.getString(0), name = c.getString(1), organisationId = c.getString(2),
                        department = c.getStringOrNull(3), phone = c.getStringOrNull(4), email = c.getStringOrNull(5),
                        createdAt = c.getLong(6), sourceDocumentId = c.getStringOrNull(7), sourceEntityName = c.getStringOrNull(8),
                    )
                } else {
                    null
                }
            }.toList()
        }
    }

    private fun documentExists(db: SupportSQLiteDatabase, documentId: String): Boolean =
        db.query("SELECT 1 FROM `documents` WHERE `id` = ?", arrayOf(documentId)).use { it.moveToFirst() }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toSet()
        }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
