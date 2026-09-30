package com.postsaimanager.core.data.database

import com.postsaimanager.core.domain.extraction.v2.LegacyTypes

/**
 * The SQL of `MIGRATION_14_15` that rewrites the type ids stored before extraction-v2-2 into family
 * ids and topics. Built from [LegacyTypes.BY_TYPE], the one owner of that mapping, so the migration
 * cannot drift from it. Pure (strings only), so a JVM test can check the statements without a device.
 */
internal object LegacyTypeSql {

    /** The statements, in the order they must run: topics first (they read the old type), then the family ids. */
    fun statements(mappings: Map<String, LegacyTypes.Mapping> = LegacyTypes.BY_TYPE): List<String> =
        listOfNotNull(topicsStatement(mappings), familyStatement(mappings), familySourceStatement())

    /** Fills `topics` from the legacy type, only for rows whose topics are still empty. */
    private fun topicsStatement(mappings: Map<String, LegacyTypes.Mapping>): String? {
        val withTopics = mappings.filterValues { it.topics.isNotEmpty() }
        if (withTopics.isEmpty()) return null
        val cases = withTopics.entries.joinToString(" ") { (type, mapping) ->
            "WHEN ${literal(type)} THEN '${jsonList(mapping.topics)}'"
        }
        return "UPDATE `documents` SET `topics` = CASE ${typeKey()} $cases END " +
            "WHERE `extractionType` IS NOT NULL AND (`topics` IS NULL OR `topics` = '' OR `topics` = '[]') " +
            "AND ${typeKey()} IN (${withTopics.keys.joinToString(", ") { literal(it) }})"
    }

    /** Rewrites `extractionType` from a legacy type id to its family id. */
    private fun familyStatement(mappings: Map<String, LegacyTypes.Mapping>): String? {
        if (mappings.isEmpty()) return null
        val cases = mappings.entries.joinToString(" ") { (type, mapping) ->
            "WHEN ${literal(type)} THEN ${literal(mapping.family)}"
        }
        return "UPDATE `documents` SET `extractionType` = CASE ${typeKey()} $cases END " +
            "WHERE ${typeKey()} IN (${mappings.keys.joinToString(", ") { literal(it) }})"
    }

    /** Every document that has a type got it from the model; a person's choice did not exist before v15. */
    private fun familySourceStatement(): String =
        "UPDATE `documents` SET `familySource` = 'MODEL' WHERE `extractionType` IS NOT NULL"

    private fun typeKey() = "lower(trim(`extractionType`))"

    private fun jsonList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${plain(it)}\"" }

    private fun literal(value: String): String = "'${plain(value)}'"

    /** Type, family and topic ids are plain identifiers; a quote or a backslash would break the SQL or the JSON, so fail loudly. */
    private fun plain(value: String): String {
        require(value.none { it == '\'' || it == '"' || it == '\\' }) { "not a plain id: $value" }
        return value
    }
}
