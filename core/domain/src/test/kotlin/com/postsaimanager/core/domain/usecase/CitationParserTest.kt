package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CitationParserTest {

    private fun labelled(vararg labels: String) =
        labels.mapIndexed { index, label -> CitationParser.Labelled(label, index) }

    @Test
    fun `no passages means nothing to pick`() {
        assertThat(CitationParser.pick("Anything [p.2]", emptyList<CitationParser.Labelled<Int>>())).isEmpty()
    }

    @Test
    fun `answer with no bracketed citation shows every injected passage`() {
        val result = CitationParser.pick("The due date is in three weeks.", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `answer citing one page shows only that page`() {
        val result = CitationParser.pick("The deadline is on the second page [p.2].", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `is lenient on spacing and case`() {
        val result = CitationParser.pick("See [P. 2] for details.", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `accepts the plain page word`() {
        val result = CitationParser.pick("Noted on [page 2].", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `accepts German Seite`() {
        val result = CitationParser.pick("Das steht auf [Seite 2].", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `accepts German S abbreviation`() {
        val result = CitationParser.pick("Frist siehe [S. 2].", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `matches a part label when there is no page number`() {
        val result = CitationParser.pick("Mentioned in [part 3].", labelled("part 1", "part 3"))
        assertThat(result).containsExactly(1)
    }

    @Test
    fun `matches several citations at once`() {
        val result = CitationParser.pick(
            "First [p.1], then later [p.3] too.",
            labelled("p.1", "p.2", "p.3"),
        )
        assertThat(result).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `falls back to every passage when the citation cannot be matched`() {
        val result = CitationParser.pick("See [p.9] for details.", labelled("p.1", "p.2"))
        assertThat(result).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `standalone labels require the title to line up, not just the page number`() {
        val result = CitationParser.pick(
            "The deadline is on [Acme Corp, p.2].",
            labelled("Acme Corp, p.2", "Contoso Ltd, p.2"),
        )
        assertThat(result).containsExactly(0)
    }

    @Test
    fun `standalone label matches without repeating the exact title casing`() {
        val result = CitationParser.pick(
            "Noted in [acme corp, S. 2].",
            labelled("Acme Corp, p.2", "Contoso Ltd, p.2"),
        )
        assertThat(result).containsExactly(0)
    }

    @Test
    fun `a bare page citation with no title still matches a titled label`() {
        // The model does not always reproduce the title — falling back to page-only
        // matching is still useful when only one candidate's page matches at all.
        val result = CitationParser.pick("See [p.2].", labelled("Acme Corp, p.2"))
        assertThat(result).containsExactly(0)
    }

    @Test
    fun `unlabelled passages join the fallback but are never individually cited`() {
        val result = CitationParser.pick(
            "No idea where this is from.",
            labelled("p.1"),
            unlabelled = listOf(99),
        )
        assertThat(result).containsExactly(0, 99).inOrder()
    }

    @Test
    fun `a specific citation excludes unlabelled passages entirely`() {
        val result = CitationParser.pick(
            "See [p.1] for the answer.",
            labelled("p.1", "p.2"),
            unlabelled = listOf(99),
        )
        assertThat(result).containsExactly(0)
    }

    @Test
    fun `preserves the order of labelled, not the order citations appear in the answer`() {
        val result = CitationParser.pick(
            "First [p.3], but really see [p.1].",
            labelled("p.1", "p.2", "p.3"),
        )
        assertThat(result).containsExactly(0, 2).inOrder()
    }
}
