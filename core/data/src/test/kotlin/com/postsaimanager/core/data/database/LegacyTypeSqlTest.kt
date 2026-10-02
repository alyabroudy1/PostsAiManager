package com.postsaimanager.core.data.database

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The SQL of MIGRATION_14_15's legacy mapping. It cannot run here (no SQLite on the JVM), so this pins its
 * shape; the instrumented `MigrationTest.migrate14To15...` runs it on a device (P4).
 */
class LegacyTypeSqlTest {

    private val statements = LegacyTypeSql.statements()

    @Test
    fun `topics are filled first, because they read the old type`() {
        assertThat(statements[0]).startsWith("UPDATE `documents` SET `topics`")
        assertThat(statements[1]).startsWith("UPDATE `documents` SET `extractionType`")
    }

    @Test
    fun `every legacy type maps to its family in the CASE`() {
        val family = statements[1]
        for ((type, mapping) in LegacyTypes.BY_TYPE) {
            assertThat(family).contains("WHEN '$type' THEN '${mapping.family}'")
        }
    }

    @Test
    fun `topics are written as a JSON list, and only for types that have topics`() {
        val topics = statements[0]
        assertThat(topics).contains("WHEN 'authority_tax' THEN '[\"government\"]'")
        assertThat(topics).contains("WHEN 'insurance_contract' THEN '[\"insurance\"]'")
        assertThat(topics).doesNotContain("WHEN 'bill'")
        assertThat(topics).doesNotContain("WHEN 'other'")
    }

    @Test
    fun `only rows whose topics are still empty get the legacy topics`() {
        assertThat(statements[0]).contains("(`topics` IS NULL OR `topics` = '' OR `topics` = '[]')")
    }

    @Test
    fun `the type is matched case and space insensitively, and a family id is left alone`() {
        assertThat(statements[1]).contains("lower(trim(`extractionType`)) IN (")
        assertThat(statements[1].substringAfter(" IN (")).doesNotContain("invoice_bill")
    }

    @Test
    fun `a model-chosen family is marked as the model's`() {
        assertThat(statements.last()).contains("`familySource` = 'MODEL'")
    }

    @Test
    fun `an id that would break the SQL is refused`() {
        assertThrows<IllegalArgumentException> {
            LegacyTypeSql.statements(mapOf("o'x" to LegacyTypes.Mapping("free_form")))
        }
    }

    @Test
    fun `an empty mapping produces nothing to rewrite`() {
        assertThat(LegacyTypeSql.statements(emptyMap())).hasSize(1)
    }
}
