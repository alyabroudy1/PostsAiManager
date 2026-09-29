package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CitationChipsTest {

    private fun source(page: Int?, doc: String = "d1", title: String? = null) =
        ChatSource(documentId = doc, pageNumber = page, title = title)

    @Test
    fun `chunks from the same page collapse to one chip`() {
        val chips = pickVisibleSources("No citation here.", listOf(source(6), source(6), source(6)))
        assertThat(chips.map { it.pageNumber }).containsExactly(6)
    }

    @Test
    fun `same page of different documents stays separate`() {
        val chips = pickVisibleSources(
            "No citation here.",
            listOf(source(1, "a"), source(1, "b"), source(1, "a")),
        )
        assertThat(chips.map { it.documentId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `cited pages come first-mention ordered and duplicates are gone`() {
        val chips = pickVisibleSources(
            "Betrag auf [p.4], Frist auf [p.1], nochmal [p.4].",
            listOf(source(1), source(3), source(4), source(4), source(1)),
        )
        assertThat(chips.map { it.pageNumber }).containsExactly(4, 1).inOrder()
    }

    @Test
    fun `titled labels are matched in a standalone chat`() {
        val chips = pickVisibleSources(
            "Siehe [Einkommensteuerbescheid 2025, p.4].",
            listOf(source(4, "t", "Einkommensteuerbescheid 2025"), source(4, "x", "Rechnung")),
        )
        assertThat(chips.map { it.documentId }).containsExactly("t")
    }

    @Test
    fun `excerpts without a page number are deduplicated per document`() {
        val chips = pickVisibleSources("Kein Marker.", listOf(source(null), source(null)))
        assertThat(chips).hasSize(1)
    }
}
