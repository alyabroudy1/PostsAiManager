package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.CandidateTable
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import com.postsaimanager.core.domain.extraction.zones.TemplateMatcher
import com.postsaimanager.core.domain.extraction.zones.ZonedLetter
import org.junit.jupiter.api.Test

/**
 * Where a name is printed decides whether it can be a party or a person: a cell of a table (a column header, a position) never is. Run on
 * the real OCR of the benchmark letters, by geometry only.
 */
class TableCellNamesTest {

    private fun zonedOf(key: String): ZonedLetter {
        val fixture = BenchmarkFixtures.load().docs.first { it.first.key == key }.second
        val pages = fixture.pages.map { it.blocks }
        val layout = LetterLayoutAnalyzer.analyze(pages)
        val offered = CandidateTable.build(ExtractorCandidateSource().find(pages, layout))
        val aspect = fixture.pages.first().let { if (it.height > 0) it.width.toFloat() / it.height else null }
        return ZonedLetter(layout, TemplateMatcher().match(layout, aspect).template, offered)
    }

    private fun names(zoned: ZonedLetter) = zoned.offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }

    @Test
    fun `the column headers of the invoice's table are table cells, its sender and addressee are not`() {
        val zoned = zonedOf("invoice-2p")
        val names = names(zoned)
        val tableNames = names.filter { zoned.isTableCell(it) }.map { it.raw.trim() }
        val otherNames = names.filterNot { zoned.isTableCell(it) }.map { it.raw.trim() }
        assertThat(tableNames.any { it.contains("Einzelpreis") }).isTrue()
        assertThat(tableNames.any { it.contains("Pos. Beschreibung") }).isTrue()
        assertThat(otherNames.any { it.contains("Musterfirma GmbH") }).isTrue()
        assertThat(otherNames.any { it.contains("Erika Mustermann") }).isTrue()
    }

    @Test
    fun `a school's name printed in the letterhead and again in the footer is not a table cell`() {
        val zoned = zonedOf("N3-schule-familie-2p")
        val school = names(zoned).first { it.raw.trim() == "Grundschule Am Beispielweg" }
        assertThat(zoned.isTableCell(school)).isFalse()
    }
}
