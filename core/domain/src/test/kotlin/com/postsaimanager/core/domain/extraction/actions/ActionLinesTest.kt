package com.postsaimanager.core.domain.extraction.actions

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ActionLinesTest {

    private fun field(slot: String, value: String, state: ReviewState = ReviewState.UNREVIEWED, name: String = slot) = ExtractedData(
        id = "id-$slot", documentId = "d", fieldName = name, fieldValue = value, fieldType = ExtractedFieldType.OTHER, confidence = 0.9f,
        slotKey = slot, reviewState = state, source = if (state == ReviewState.EDITED) ValueSource.USER else ValueSource.MACHINE,
    )

    private val fields = listOf(
        field("sender", "Musterfirma GmbH"),
        field("total", "1.284,50 €"),
        field("due_date", "15.10.2026"),
        field("iban", "DE89 3704 0044 0532 0130 00"),
        field("invoice_no", "RE-2026-0815"),
    )

    private val pay = ActionItem("pay", mapOf("date" to "due_date", "amount" to "total", "party" to "sender", "iban" to "iban", "reference" to "invoice_no"))

    @Test
    fun `an action is resolved from the live fields it is bound to`() {
        val line = ActionLines.resolve(listOf(pay), fields).single()
        assertThat(line.kind.id).isEqualTo("pay")
        assertThat(line.amount).isEqualTo("1.284,50 €")
        assertThat(line.party).isEqualTo("Musterfirma GmbH")
        assertThat(line.date).isEqualTo(ActionDate(LocalDate.of(2026, 10, 15)))
        // The fields to confirm or edit: the date, the amount, the account and the reference, never the party.
        assertThat(line.rows.map { it.slotKey }).containsExactly("due_date", "total", "iban", "invoice_no").inOrder()
        // The account and the reference are shown under the sentence to copy; the date and the amount are in the sentence.
        assertThat(line.valueRows.map { it.slotKey }).containsExactly("iban", "invoice_no").inOrder()
    }

    private val mueller = ContactPerson("c2", "jc", "Frau Müller", phone = "030 222", email = "mueller@jc.example", firstSeen = 1, lastSeen = 2)
    private val contactAction = ActionItem("contact", mapOf("party" to "sender"))

    @Test
    fun `a contact action offers the current contact's phone and e-mail when the letter has none`() {
        val line = ActionLines.resolve(listOf(contactAction), fields, mueller).single()

        assertThat(line.offer).isEqualTo(ContactOffer("Frau Müller", "030 222", "mueller@jc.example"))
    }

    @Test
    fun `only the channel the letter gives no value for is offered`() {
        val withPhone = fields + ExtractedData("p", "d", "Phone", "030 999", ExtractedFieldType.PHONE, 0.9f, slotKey = "x:phone")

        val line = ActionLines.resolve(listOf(contactAction), withPhone, mueller).single()

        assertThat(line.offer).isEqualTo(ContactOffer("Frau Müller", null, "mueller@jc.example"))
    }

    @Test
    fun `no offer when the letter has both, for another kind of action, or without a contact`() {
        val both = fields +
            ExtractedData("p", "d", "Phone", "030 999", ExtractedFieldType.PHONE, 0.9f, slotKey = "x:phone") +
            ExtractedData("e", "d", "Email", "a@jc.example", ExtractedFieldType.EMAIL, 0.9f, slotKey = "x:email")

        assertThat(ActionLines.resolve(listOf(contactAction), both, mueller).single().offer).isNull()
        assertThat(ActionLines.resolve(listOf(pay), fields, mueller).single().offer).isNull()
        assertThat(ActionLines.resolve(listOf(contactAction), fields).single().offer).isNull()
        assertThat(ActionLines.resolve(listOf(contactAction), fields, mueller.copy(phone = null, email = " ")).single().offer).isNull()
    }

    @Test
    fun `a value a person corrected is the value the line states`() {
        val corrected = fields.map { if (it.slotKey == "total") field("total", "1.248,50 €", ReviewState.EDITED) else it }
        assertThat(ActionLines.resolve(listOf(pay), corrected).single().amount).isEqualTo("1.248,50 €")
    }

    @Test
    fun `a field a person ignored or deleted drops out of the line, which is then shorter`() {
        val ignored = fields.map { if (it.slotKey == "total") field("total", "1.284,50 €", ReviewState.IGNORED) else it }
        val line = ActionLines.resolve(listOf(pay), ignored).single()
        assertThat(line.amount).isNull()
        assertThat(line.rows.map { it.slotKey }).doesNotContain("total")
        val deleted = fields.map { if (it.slotKey == "due_date") it.copy(deletedByUser = true) else it }
        assertThat(ActionLines.resolve(listOf(pay), deleted).single().date).isNull()
    }

    @Test
    fun `a bound date that is words and not a date goes under the sentence as printed`() {
        val words = fields.map { if (it.slotKey == "due_date") field("due_date", "innerhalb von 14 Tagen nach Zugang") else it }
        val line = ActionLines.resolve(listOf(pay), words).single()
        assertThat(line.date).isNull()
        assertThat(line.valueRows.map { it.slotKey }).contains("due_date")
    }

    @Test
    fun `an appointment keeps its time`() {
        val appointment = listOf(field("appointment", "Donnerstag, 12.11.2026 um 09:30 Uhr"))
        val line = ActionLines.resolve(listOf(ActionItem("attend", mapOf("date" to "appointment"))), appointment).single()
        assertThat(line.date).isEqualTo(ActionDate(LocalDate.of(2026, 11, 12), LocalTime.of(9, 30)))
    }

    @Test
    fun `a kind this build does not know is not shown, and a binding to a field that is gone is simply missing`() {
        val items = listOf(ActionItem("teleport"), ActionItem("reply", mapOf("party" to "sender", "date" to "no_such_slot")))
        val lines = ActionLines.resolve(items, fields)
        assertThat(lines.map { it.kind.id }).containsExactly("reply")
        assertThat(lines.single().date).isNull()
        assertThat(lines.single().party).isEqualTo("Musterfirma GmbH")
    }

    @Test
    fun `no actions resolve to no lines`() {
        assertThat(ActionLines.resolve(emptyList(), fields)).isEmpty()
    }
}
