package com.postsaimanager.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The whole of the profile-timeline migration (additive and idempotent), self-contained so it can be renumbered by changing the one
 * `Migration(from, to)` that calls [apply]: the tables `cases`, `profile_events` (not the document processing log `timeline_events`) and
 * `profile_event_people`, with their indices. Nothing is read or moved: the tables start empty and fill as documents are read.
 *
 * Every statement is `IF NOT EXISTS`, so a repeated run, or a database that already has some of them, changes nothing.
 */
internal object TimelineMigration {

    val statements: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `cases` (`id` TEXT NOT NULL, `organisationProfileId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`referenceKeys` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`organisationProfileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_cases_organisationProfileId` ON `cases` (`organisationProfileId`)",
        "CREATE TABLE IF NOT EXISTS `profile_events` (`id` TEXT NOT NULL, `documentId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`eventDate` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `organisationProfileId` TEXT, " +
            "`contactId` TEXT, `caseId` TEXT, `source` TEXT NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`organisationProfileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
            "FOREIGN KEY(`contactId`) REFERENCES `contact_persons`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
            "FOREIGN KEY(`caseId`) REFERENCES `cases`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_profile_events_documentId` ON `profile_events` (`documentId`)",
        "CREATE INDEX IF NOT EXISTS `index_profile_events_organisationProfileId` ON `profile_events` (`organisationProfileId`)",
        "CREATE INDEX IF NOT EXISTS `index_profile_events_contactId` ON `profile_events` (`contactId`)",
        "CREATE INDEX IF NOT EXISTS `index_profile_events_caseId` ON `profile_events` (`caseId`)",
        "CREATE INDEX IF NOT EXISTS `index_profile_events_eventDate` ON `profile_events` (`eventDate`)",
        "CREATE TABLE IF NOT EXISTS `profile_event_people` (`eventId` TEXT NOT NULL, `profileId` TEXT NOT NULL, " +
            "PRIMARY KEY(`eventId`, `profileId`), " +
            "FOREIGN KEY(`eventId`) REFERENCES `profile_events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_profile_event_people_eventId` ON `profile_event_people` (`eventId`)",
        "CREATE INDEX IF NOT EXISTS `index_profile_event_people_profileId` ON `profile_event_people` (`profileId`)",
    )

    fun apply(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
}
