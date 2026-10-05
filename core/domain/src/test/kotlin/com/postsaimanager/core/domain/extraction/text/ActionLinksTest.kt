package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test

class ActionLinksTest {

    private fun row(
        id: String, value: String, slot: String?, machine: String? = null, edited: Boolean = false, deleted: Boolean = false,
    ) = ExtractedData(
        id = id, documentId = "d", fieldName = slot.orEmpty(), fieldValue = value, fieldType = ExtractedFieldType.OTHER, confidence = 0.9f,
        slotKey = slot, machineValue = machine, source = if (edited) ValueSource.USER else ValueSource.MACHINE, isConfirmed = edited,
        deletedByUser = deleted,
    )

    private val amount = row("a", "1.284,50 €", "total")
    private val due = row("b", "19.08.2026", "due_date")
    private val iban = row("c", "DE02 1203 0000 0000 2020 51", "iban")

    @Test
    fun `a line is linked to the fields whose every word and number it states`() {
        val linked = ActionLinks.link(listOf("Zahle 1.284,50 € bis zum 19.08.2026."), listOf(amount, due, iban))
        assertThat(linked.single().rows.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `a line that states no value is kept with no fields`() {
        val linked = ActionLinks.link(listOf("Antworte dem Absender."), listOf(amount, due))
        assertThat(linked.single().rows).isEmpty()
    }

    @Test
    fun `the parties, the subject and the address parts are never linked`() {
        val rows = listOf(
            row("s", "Musterfirma GmbH", "sender"), row("r", "Erika Mustermann", "addressee"), row("j", "Rechnung 771204", "subject"),
            row("p", "Berlin", "addressee.city"),
        )
        val linked = ActionLinks.link(listOf("Musterfirma GmbH schickt Erika Mustermann die Rechnung 771204 nach Berlin."), rows)
        assertThat(linked.single().rows).isEmpty()
    }

    @Test
    fun `a value too short to say which field it is does not link`() {
        val linked = ActionLinks.link(listOf("Zahle 3 Raten."), listOf(row("n", "3", "reference")))
        assertThat(linked.single().rows).isEmpty()
    }

    @Test
    fun `an ignored field is not behind a line`() {
        val linked = ActionLinks.link(listOf("Zahle 1.284,50 €."), listOf(row("a", "1.284,50 €", "total", deleted = true)))
        assertThat(linked.single().rows).isEmpty()
    }

    @Test
    fun `a line is stale once a person changed a value it quotes, and is not shown`() {
        val edited = row("a", "1.200,00 €", "total", machine = "1.284,50 €", edited = true)
        val linked = ActionLinks.link(listOf("Zahle 1.284,50 € bis zum 19.08.2026.", "Antworte dem Absender."), listOf(edited, due))
        assertThat(linked.map { it.text }).containsExactly("Antworte dem Absender.")
    }

    @Test
    fun `a value the person confirmed unchanged keeps its line`() {
        val confirmed = row("a", "1.284,50 €", "total", machine = "1.284,50 €", edited = true)
        val linked = ActionLinks.link(listOf("Zahle 1.284,50 €."), listOf(confirmed))
        assertThat(linked.single().rows.map { it.id }).containsExactly("a")
    }
}
