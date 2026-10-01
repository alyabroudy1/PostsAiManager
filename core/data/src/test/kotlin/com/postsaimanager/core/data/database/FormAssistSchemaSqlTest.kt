package com.postsaimanager.core.data.database

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FormAssistSchemaSqlTest {

    @Test
    fun `the migration adds the three profile columns and the facts table with its indexes`() {
        val sql = FormAssistSchemaSql.statements()

        assertThat(sql.filter { it.startsWith("ALTER TABLE `profiles` ADD COLUMN") }).hasSize(3)
        assertThat(sql).contains("ALTER TABLE `profiles` ADD COLUMN `sensitive` INTEGER NOT NULL DEFAULT 0")
        val create = sql.single { it.startsWith("CREATE TABLE IF NOT EXISTS `profile_facts`") }
        assertThat(create).contains("REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE")
        assertThat(sql.any { it.startsWith("CREATE UNIQUE INDEX") && it.contains("(`profileId`, `key`)") }).isTrue()
        assertThat(sql.any { it.startsWith("CREATE INDEX") && it.contains("(`profileId`)") }).isTrue()
    }

    @Test
    fun `the fills and fields tables go with their parents and are indexed by them`() {
        val sql = FormAssistSchemaSql.statements()

        val fills = sql.single { it.startsWith("CREATE TABLE IF NOT EXISTS `form_fills`") }
        assertThat(fills).contains("REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE")
        val fields = sql.single { it.startsWith("CREATE TABLE IF NOT EXISTS `form_fields`") }
        assertThat(fields).contains("REFERENCES `form_fills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE")
        assertThat(sql.any { it.startsWith("CREATE INDEX") && it.contains("`form_fields` (`formFillId`)") }).isTrue()
        assertThat(sql.indexOf(fills)).isLessThan(sql.indexOf(fields))
    }

    @Test
    fun `the columns run before the facts table, which is additive only`() {
        val sql = FormAssistSchemaSql.statements()

        assertThat(sql.none { it.contains("DROP", ignoreCase = true) }).isTrue()
        assertThat(sql.indexOfFirst { it.startsWith("ALTER") }).isLessThan(sql.indexOfFirst { it.startsWith("CREATE TABLE") })
    }
}
