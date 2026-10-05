package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import org.junit.jupiter.api.Test

class ComposedTextsTest {

    private val labels = mapOf("invoice_bill" to "Rechnung", "free_form" to "Sonstiges")

    @Test
    fun `a composed title is family, sender and subject joined, from the args the composer wrote`() {
        val composed = TitleComposer.compose("invoice_bill", "Telekom", "Rechnung Oktober")!!
        assertThat(ComposedTitle.render(composed.args, labels::get)).isEqualTo("Rechnung · Telekom · Rechnung Oktober")
    }

    @Test
    fun `an empty slot is dropped without shifting the next one`() {
        assertThat(ComposedTitle.render(listOf("invoice_bill", "", "Mahnung"), labels::get)).isEqualTo("Rechnung · Mahnung")
        assertThat(ComposedTitle.render(listOf("free_form", "Telekom", ""), labels::get)).isEqualTo("Sonstiges · Telekom")
        assertThat(ComposedTitle.render(listOf("", "Telekom", ""), labels::get)).isEqualTo("Telekom")
    }

    @Test
    fun `a family with no label is shown as plain words, and nothing at all is null`() {
        assertThat(ComposedTitle.render(listOf("ticket_booking", "", ""), labels::get)).isEqualTo("ticket booking")
        assertThat(ComposedTitle.render(listOf("", "", ""), labels::get)).isNull()
        assertThat(ComposedTitle.render(emptyList(), labels::get)).isNull()
    }

    /** English stand-ins for the string resources, one per piece, so the rule is tested without Android. */
    private val format: (SummaryPiece, List<String>) -> String = { piece, p ->
        when (piece) {
            SummaryPiece.INTRO -> "${p[0]}."
            SummaryPiece.INTRO_FROM -> "${p[0]} from ${p[1]}."
            SummaryPiece.INTRO_FOR -> "${p[0]} for ${p[1]}."
            SummaryPiece.INTRO_FROM_FOR -> "${p[0]} from ${p[1]} for ${p[2]}."
            SummaryPiece.AMOUNT_DUE -> "Amount ${p[0]}, due ${p[1]}."
            SummaryPiece.AMOUNT -> "Amount ${p[0]}."
            SummaryPiece.DUE -> "Due ${p[0]}."
            SummaryPiece.SUBJECT -> "About: ${p[0]}."
        }
    }

    private fun render(args: List<String>) = TemplateSummary.render(args, labels::get, format)

    @Test
    fun `a full template summary names everything the letter gave`() {
        val args = SummaryFacts(
            "invoice_bill", sender = "Telekom", addressee = "Erika", amount = "64,98 €", dueDate = "15.10.2026", subject = "Rechnung",
        ).templateArgs()
        assertThat(render(args)).isEqualTo("Rechnung from Telekom for Erika. Amount 64,98 €, due 15.10.2026. About: Rechnung.")
    }

    @Test
    fun `missing parts leave no gap and no dangling word`() {
        assertThat(render(listOf("invoice_bill", "Telekom", "", "", "", ""))).isEqualTo("Rechnung from Telekom.")
        assertThat(render(listOf("invoice_bill", "", "Erika", "", "", ""))).isEqualTo("Rechnung for Erika.")
        assertThat(render(listOf("invoice_bill", "", "", "64,98 €", "", ""))).isEqualTo("Rechnung. Amount 64,98 €.")
        assertThat(render(listOf("invoice_bill", "", "", "", "15.10.2026", ""))).isEqualTo("Rechnung. Due 15.10.2026.")
        assertThat(render(listOf("", "Telekom", "", "", "", "Mahnung"))).isEqualTo("About: Mahnung.")
    }

    @Test
    fun `args with nothing to say, or too few, render to null or what is there`() {
        assertThat(render(listOf("", "", "", "", "", ""))).isNull()
        assertThat(render(emptyList())).isNull()
        assertThat(render(listOf("free_form"))).isEqualTo("Sonstiges.")
    }

    @Test
    fun `the args of the writer and the positions of the renderer agree`() {
        val facts = SummaryFacts("invoice_bill", sender = "S", addressee = "A", amount = "1 €", dueDate = "D", subject = "X")
        assertThat(facts.templateArgs()).containsExactly("invoice_bill", "S", "A", "1 €", "D", "X").inOrder()
    }
}
