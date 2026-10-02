package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test

/** A second stage whose ticket was lost is rebuilt from what the first stage stored: the family, the topics and the fields. */
class EnrichmentTicketRebuilderTest {

    private val document = Document(
        id = "d", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1,
        extractionType = "invoice_bill", topics = listOf("housing_utilities", "tax"),
    )

    private fun field(
        slot: String?, value: String, state: ReviewState = ReviewState.UNREVIEWED, machine: String? = value, source: ValueSource = ValueSource.MACHINE,
    ) = ExtractedData(
        id = "id-$slot-$value", documentId = "d", fieldName = slot ?: value, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.8f,
        slotKey = slot, source = source, machineValue = machine, reviewState = state,
        isConfirmed = state == ReviewState.CONFIRMED || state == ReviewState.EDITED, deletedByUser = state == ReviewState.IGNORED,
    )

    @Test
    fun `the family and the topics are the document's`() {
        val ticket = EnrichmentTicketRebuilder.rebuild(document, emptyList())
        assertThat(ticket.typeId).isEqualTo("invoice_bill")
        assertThat(ticket.topics).containsExactly("housing_utilities", "tax").inOrder()
        assertThat(ticket.takenIds).isEmpty()
        assertThat(ticket.established).isEmpty()
    }

    @Test
    fun `the facts are the stored first stage values under the roles the summary names`() {
        val ticket = EnrichmentTicketRebuilder.rebuild(
            document,
            listOf(
                field("sender", "Stadtwerke Beispiel"), field("addressee", "Erika Mustermann"), field("total", "64,98 €"),
                field("due_date", "15.10.2026"), field("letter_date", "28.09.2026"), field("reference", "RE-2026-0815"), field("iban", "DE89"),
            ),
        )
        assertThat(ticket.facts).containsExactly(
            "sender", "Stadtwerke Beispiel", "addressed_to", "Erika Mustermann", "amount", "64,98 €", "due_date", "15.10.2026",
            "date", "28.09.2026", "reference", "RE-2026-0815",
        )
    }

    @Test
    fun `a value a person corrected is the fact, and one a person ignored is none`() {
        val ticket = EnrichmentTicketRebuilder.rebuild(
            document,
            listOf(
                field("total", "70,00 €", state = ReviewState.EDITED, machine = "64,98 €", source = ValueSource.USER),
                field("sender", "Falscher Absender", state = ReviewState.IGNORED),
            ),
        )
        assertThat(ticket.facts).containsEntry("amount", "70,00 €")
        assertThat(ticket.facts).doesNotContainKey("sender")
        assertThat(ticket.takenValues).doesNotContain("Falscher Absender")
    }

    @Test
    fun `the values of the first stage's fields stand for the candidate ids it took, the second stage's own rows do not`() {
        val ticket = EnrichmentTicketRebuilder.rebuild(
            document,
            listOf(
                field("total", "70,00 €", state = ReviewState.EDITED, machine = "64,98 €", source = ValueSource.USER),
                field("x:tarif", "Basis"),
                field("subject", "Rechnung Juli"),
            ),
        )
        // Both what a person set and what the machine read, as either may be what the letter prints.
        assertThat(ticket.takenValues).containsAtLeast("70,00 €", "64,98 €")
        assertThat(ticket.takenValues).containsNoneOf("Basis", "Rechnung Juli")
    }

    @Test
    fun `a document with no family gives a ticket with none`() {
        assertThat(EnrichmentTicketRebuilder.rebuild(document.copy(extractionType = null), emptyList()).typeId).isNull()
    }
}
