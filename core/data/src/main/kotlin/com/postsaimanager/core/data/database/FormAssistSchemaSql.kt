package com.postsaimanager.core.data.database

/**
 * The SQL of `MIGRATION_15_16` (form assist, additive). One list per concern, so a later phase adds its own list and
 * appends it to [PamMigrations.MIGRATION_15_16] instead of editing the statements below. Pure strings, so a JVM test
 * can check them without a device.
 */
internal object FormAssistSchemaSql {

    /** Family profiles: how a person relates to "Me", their birth date, and the sensitive-person flag. */
    val profileColumns: List<String> = listOf(
        "ALTER TABLE `profiles` ADD COLUMN `relationship` TEXT",
        "ALTER TABLE `profiles` ADD COLUMN `birthDate` TEXT",
        "ALTER TABLE `profiles` ADD COLUMN `sensitive` INTEGER NOT NULL DEFAULT 0",
    )

    /** "Saved details": one remembered value per (profile, key). */
    val profileFacts: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `profile_facts` (`id` TEXT NOT NULL, `profileId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceDocumentId` TEXT, `sensitive` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_profile_facts_profileId` ON `profile_facts` (`profileId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_facts_profileId_key` ON `profile_facts` (`profileId`, `key`)",
    )

    /** Every statement of the v15 to v16 migration, in the order they run. */
    fun statements(): List<String> = profileColumns + profileFacts
}
