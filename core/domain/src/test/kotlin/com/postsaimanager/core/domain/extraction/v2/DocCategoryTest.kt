package com.postsaimanager.core.domain.extraction.v2

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.text.ReadFacts
import com.postsaimanager.core.model.DocumentType
import com.postsaimanager.core.model.TicketSlot
import org.junit.jupiter.api.Test

/** The broad categories: a short list over the registry's families, as data, with no stored id changed. */
class DocCategoryTest {

    private val schema = ExtractionSchema.DEFAULT

    @Test
    fun `there are nine categories, each standing for families the registry holds, none for two categories`() {
        assertThat(schema.categories.map { it.id }).containsExactly(
            "letter", "bill", "receipt", "form", "statement", "contract", "appointment", "message", "notice",
        ).inOrder()
        val standing = schema.categories.flatMap { it.families }
        assertThat(standing).containsNoDuplicates()
        for (id in standing) assertThat(schema.family(id)).isNotNull()
        // Every family but the neutral Document is a category's, so every stored id of a document has a category.
        assertThat(schema.families.map { it.id } - standing.toSet()).containsExactly("free_form")
        for (c in schema.categories) assertThat(c.phrase).isNotEmpty()
    }

    @Test
    fun `a stored id, a legacy id included, is read as its category, and the neutral Document and unknown ids have none`() {
        assertThat(schema.categoryOf("official_letter")?.id).isEqualTo("letter")
        assertThat(schema.categoryOf("outgoing_letter")?.id).isEqualTo("letter")
        assertThat(schema.categoryOf("invoice_bill")?.id).isEqualTo("bill")
        assertThat(schema.categoryOf("medical")?.id).isEqualTo("appointment")
        assertThat(schema.categoryOf("ticket_booking")?.id).isEqualTo("appointment")
        assertThat(schema.categoryOf("certificate_id")?.id).isEqualTo("notice")
        assertThat(schema.categoryOf("payment_proof")?.id).isEqualTo("receipt")
        // Legacy ids stored before extraction-v2-2 map through the family they stand for.
        assertThat(schema.categoryOf("reminder_dunning")?.id).isEqualTo("bill")
        assertThat(schema.categoryOf("health")?.id).isEqualTo("appointment")
        assertThat(schema.categoryOf(" Invoice_Bill ")?.id).isEqualTo("bill")
        assertThat(schema.categoryOf("free_form")).isNull()
        assertThat(schema.categoryOf("other")).isNull()
        assertThat(schema.categoryOf("astrology")).isNull()
        assertThat(schema.categoryOf(null)).isNull()
    }

    @Test
    fun `what is scored is one family per category, the first that a document of the direction can be`() {
        assertThat(schema.categoryFamilies(DocDirection.INCOMING).map { it.id }).containsExactly(
            "official_letter", "invoice_bill", "receipt", "form_application", "statement", "contract_policy",
            "appointment_reminder", "message_note", "notice_decision",
        ).inOrder()
        assertThat(schema.categoryFamilies(DocDirection.OUTGOING).map { it.id }).containsExactly("outgoing_letter")
        assertThat(schema.categoryFamilies(DocDirection.PROOF).map { it.id }).containsExactly("payment_proof")
        // The six recorded families keep their order, so the recordings' columns are found by position.
        assertThat(schema.categoryFamilies(DocDirection.INCOMING).take(6).map { it.id })
            .containsExactlyElementsIn(schema.familiesFor(DocDirection.INCOMING).map { it.id }.filter { it in setOf("official_letter", "invoice_bill", "receipt", "form_application", "statement", "contract_policy") })
            .inOrder()
        assertThat(schema.categoryFamilies(DocDirection.INCOMING).map { it.id }).doesNotContain("free_form")
    }

    @Test
    fun `a schema that names no categories has one per scored family, as it always did`() {
        val a = DocFamily.of("a", DocumentType.OTHER).described("a thing a")
        val b = DocFamily.of("b", DocumentType.OTHER).described("a thing b")
        val free = DocFamily.of("free", DocumentType.OTHER).unscored().forDirections(*DocDirection.entries.toTypedArray())
        val plain = ExtractionSchema(listOf(a, b, free))
        assertThat(plain.categories.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(plain.categoryFamilies(DocDirection.INCOMING).map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(plain.categoryOf("b")?.id).isEqualTo("b")
    }

    @Test
    fun `before the type is known, a received letter is asked every slot of the families it can be and no other`() {
        val slots = schema.slotsFor(DocDirection.INCOMING)
        assertThat(slots).containsAtLeastElementsIn(Slots.CORE)
        assertThat(slots).containsAtLeast(Slots.APPOINTMENT, Slots.FEE, Slots.NEW_AMOUNT, Slots.RECEIPT_NO, Slots.OBJECTION_DEADLINE)
        assertThat(slots).containsNoneOf(Slots.RECIPIENT_ORG, Slots.SENT_DATE, Slots.PROOF_AMOUNT, Slots.CITED_REFERENCES)
        assertThat(slots).containsNoDuplicates()
        assertThat(schema.slotsFor(DocDirection.OUTGOING)).contains(Slots.RECIPIENT_ORG)
    }

    @Test
    fun `the facts block names the sender, the recipient and every stored value with its meaning, and can be taken off again`() {
        val block = ReadFacts.block(
            mapOf("sender" to "Zahnarztpraxis Dr. Weber", "addressed_to" to "Erika Mustermann"),
            listOf(
                TicketSlot("appointment", "Appointment", "14.10.2026", "the date of an appointment or a meeting the reader is to attend"),
                TicketSlot("total", "Amount", "45,00 EUR"),
                TicketSlot("iban", "IBAN", "  "),
            ),
        )
        assertThat(block).contains("- sender: Zahnarztpraxis Dr. Weber")
        assertThat(block).contains("- addressed to: Erika Mustermann")
        assertThat(block).contains("- Appointment: 14.10.2026 (the date of an appointment or a meeting the reader is to attend)")
        assertThat(block).contains("- Amount: 45,00 EUR\n")
        assertThat(block).doesNotContain("IBAN")
        assertThat(block).endsWith("${ReadFacts.END}\n")
        val question = "Is this document a receipt? Answer:"
        assertThat(ReadFacts.strip(block + question)).isEqualTo(question)
        assertThat(ReadFacts.strip(question)).isEqualTo(question)
        assertThat(ReadFacts.block(emptyMap(), emptyList())).isEmpty()
        // Bounded: the questions it precedes are asked once per category.
        val many = ReadFacts.block(emptyMap(), (1..40).map { TicketSlot("k$it", "Label $it", "value $it") })
        assertThat(many.lines().count { it.startsWith("- ") }).isEqualTo(ReadFacts.MAX_LINES)
    }
}
