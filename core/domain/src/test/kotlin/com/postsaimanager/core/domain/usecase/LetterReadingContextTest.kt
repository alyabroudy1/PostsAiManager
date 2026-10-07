package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.letterContactsFor
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LetterReadingContextTest {

    private fun field(slot: String, name: String, value: String, type: ExtractedFieldType = ExtractedFieldType.TEXT, deleted: Boolean = false) =
        ExtractedData(
            id = "f-$slot", documentId = "d1", fieldName = name, fieldValue = value, fieldType = type,
            confidence = 0.9f, slotKey = slot, deletedByUser = deleted,
        )

    private val fields = listOf(
        field("start_date", "Contract start", "01.01.2027", ExtractedFieldType.DATE),
        field("due_date", "Due date", "15.10.2026", ExtractedFieldType.DATE),
        field("total_amount", "Amount", "563,00 EUR"),
        field("sender", "Sender", "Stadtwerke Beispielstadt"),
        field("invoice_no", "Invoice number", "R-2026-0815"),
    )

    private val pay = ActionItem(
        "pay",
        mapOf("date" to "due_date", "amount" to "total_amount", "party" to "sender", "reference" to "invoice_no"),
    )

    @Test
    fun `an action states its deadline with what the date means, and the amount, sender and reference`() {
        val text = LetterReadingContext.section(listOf(pay), fields)

        assertThat(text).contains("## What was read from this letter")
        assertThat(text).contains("pay an amount of money")
        assertThat(text).contains("date 15.10.2026 (the date by which the reader is asked to pay)")
        assertThat(text).contains("amount 563,00 EUR")
        assertThat(text).contains("sender Stadtwerke Beispielstadt")
        assertThat(text).contains("reference R-2026-0815")
        // The contract start is not an answer of the reading, so it is not here.
        assertThat(text).doesNotContain("01.01.2027")
    }

    @Test
    fun `the slots of the values it states are the ones the grounding does not list again`() {
        assertThat(LetterReadingContext.statedSlots(listOf(pay), fields))
            .containsExactly("due_date", "total_amount", "sender", "invoice_no")
        // A deadline field no action covers is stated too; with nothing stated, nothing is left out.
        assertThat(LetterReadingContext.statedSlots(emptyList(), listOf(field("deadline", "Deadline", "31.01.2026", ExtractedFieldType.DEADLINE))))
            .containsExactly("deadline")
        assertThat(LetterReadingContext.statedSlots(emptyList(), fields)).isEmpty()
    }

    @Test
    fun `a deadline field no action covers is listed on its own`() {
        val text = LetterReadingContext.section(emptyList(), listOf(field("deadline", "Deadline", "31.01.2026", ExtractedFieldType.DEADLINE)))

        assertThat(text).contains("- Deadline: 31.01.2026")
    }

    @Test
    fun `the generated key information is listed as label and value after the answers, within the line cap, and for a letter with nothing else`() {
        val facts = listOf(
            field("x:zahlungsziel", "Zahlungsziel", "30 Tage netto"),
            field("x:المرجع", "المرجع", "REF-5521"),
            // A label the verifier replaced by its key (a collision) and a value the person ignored are not stated.
            field("x:amount", "x:amount", "9,00 EUR"),
            field("x:ignored", "Notiz", "gestrichen", deleted = true),
        )
        val alone = LetterReadingContext.section(emptyList(), facts)
        assertThat(alone).contains("- Zahlungsziel: 30 Tage netto")
        assertThat(alone).contains("- المرجع: REF-5521")
        assertThat(alone).doesNotContain("9,00 EUR")
        assertThat(alone).doesNotContain("gestrichen")

        // The action lines come first, and the whole section keeps its cap.
        val withAction = LetterReadingContext.section(listOf(pay), fields + facts).lines()
        assertThat(withAction.indexOfFirst { it.contains("pay an amount") }).isLessThan(withAction.indexOfFirst { it.contains("Zahlungsziel") })
        val many = (1..12).map { field("x:fact_$it", "Fact $it", "value $it") }
        assertThat(LetterReadingContext.section(emptyList(), many).lines().count { it.startsWith("- ") }).isEqualTo(8)
    }

    @Test
    fun `the dates and amounts the reading gave a meaning are listed with it, an appointment beside a payment`() {
        val read = listOf(
            field("due_date", "Deadline", "15.10.2026", ExtractedFieldType.DEADLINE).copy(role = "meaning:APPOINTMENT"),
            field("event_date", "Event Date", "14.10.2026 10:30", ExtractedFieldType.DATE).copy(role = "meaning:APPOINTMENT"),
            field("contract_end", "Contract End", "31.12.2026", ExtractedFieldType.DATE).copy(role = "meaning:PERIOD_END"),
            field("fee", "Fee", "5,00 EUR").copy(role = "meaning:FEE"),
            // A slot's own expected role is no meaning, and a value with none is not stated.
            field("letter_date", "Document Date", "01.10.2026", ExtractedFieldType.DATE).copy(role = "LETTER_DATE"),
            field("customer_no", "Customer Number", "KD-1"),
        )
        val text = LetterReadingContext.section(emptyList(), read)

        assertThat(text).contains("- date 14.10.2026 10:30 (the date of an appointment or a meeting the reader is to attend)")
        // The deadline field holds an appointment: stated as what the reading found it to be, once.
        assertThat(text).contains("- date 15.10.2026 (the date of an appointment or a meeting the reader is to attend)")
        assertThat(text).doesNotContain("- Deadline:")
        assertThat(text).contains("- date 31.12.2026 (the last day of a period this document covers)")
        assertThat(text).contains("- amount 5,00 EUR (a fee or a surcharge)")
        assertThat(text).doesNotContain("01.10.2026")
        assertThat(text).doesNotContain("KD-1")
    }

    @Test
    fun `an action's own meaning of its date wins, and a bound value with a meaning is not listed twice`() {
        val read = fields.map {
            when (it.slotKey) {
                "due_date" -> it.copy(role = "meaning:DUE_DATE")
                "total_amount" -> it.copy(role = "meaning:PREMIUM")
                else -> it
            }
        }
        val text = LetterReadingContext.section(listOf(pay), read)

        assertThat(text).contains("date 15.10.2026 (the date by which the reader is asked to pay)")
        assertThat(text.lines().count { it.contains("15.10.2026") }).isEqualTo(1)
        assertThat(text.lines().count { it.contains("563,00 EUR") }).isEqualTo(1)
    }

    @Test
    fun `nothing is written for a letter with no action and no deadline`() {
        assertThat(LetterReadingContext.section(emptyList(), fields)).isEmpty()
    }

    @Test
    fun `a value the person removed is not stated`() {
        val removed = fields.map { if (it.slotKey == "due_date") it.copy(deletedByUser = true) else it }

        assertThat(LetterReadingContext.section(listOf(pay), removed)).doesNotContain("15.10.2026")
    }

    @Test
    fun `the chat grounding carries the read answers before the extracted details`() = runTest {
        val documents = FakeDocumentRepository()
        documents.seed(testDocument(id = "d1").copy(actionItems = listOf(pay)))
        documents.seedExtracted("d1", *fields.toTypedArray())

        val prompt = BuildChatContextUseCase(documents, FakeProfileRepository(), letterContactsFor()).invoke("d1", contextTokens = 4096).text

        assertThat(prompt).contains("date 15.10.2026 (the date by which the reader is asked to pay)")
        assertThat(prompt.indexOf("What was read from this letter")).isLessThan(prompt.indexOf("## Extracted details"))
    }

    private val nadine = ContactPerson("nadine", "jc", "Frau Nadine Beispiel", phone = "030 111", firstSeen = 1, lastSeen = 10)
    private val mueller = ContactPerson(
        "mueller", "jc", "Frau Müller", title = "Sachbearbeiterin", phone = "030 222", email = "mueller@jc.example", firstSeen = 20, lastSeen = 30,
    )

    @Test
    fun `the letter's contact and the organisation's current contact are read facts with their phone and e-mail`() {
        val text = LetterReadingContext.section(
            emptyList(), emptyList(),
            LetterContacts(letterContact = nadine, current = mueller, organisationId = "jc", organisationName = "Jobcenter Musterstadt"),
        )

        assertThat(text).contains("## What was read from this letter")
        assertThat(text).contains("The contact person named in this letter: Frau Nadine Beispiel, phone 030 111")
        assertThat(text).contains("The current contact at Jobcenter Musterstadt: Frau Müller, Sachbearbeiterin, phone 030 222, email mueller@jc.example")
    }

    @Test
    fun `one person who is both is stated once`() {
        val text = LetterReadingContext.section(emptyList(), emptyList(), LetterContacts(letterContact = mueller, current = mueller, organisationName = "Jobcenter Musterstadt"))

        assertThat(text).contains("The contact person named in this letter: Frau Müller")
        assertThat(text).contains("Frau Müller is also the organisation's current contact at Jobcenter Musterstadt")
        assertThat(text.split("mueller@jc.example")).hasSize(2)
    }

    @Test
    fun `the chat grounding offers the contacts so who is my contact there is answered from the context`() = runTest {
        val documents = FakeDocumentRepository()
        val profiles = FakeProfileRepository()
        val contacts = FakeContactRepository()
        documents.seed(testDocument(id = "d1"))
        profiles.seed(testProfile(id = "jc", name = "Jobcenter Musterstadt"))
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
        contacts.seed(nadine, mueller)
        contacts.linkContactToDocument("nadine", "d1")

        val prompt = BuildChatContextUseCase(documents, profiles, letterContactsFor(profiles, contacts)).invoke("d1", contextTokens = 4096).text

        assertThat(prompt).contains("The contact person named in this letter: Frau Nadine Beispiel")
        assertThat(prompt).contains("The current contact at Jobcenter Musterstadt: Frau Müller")
        assertThat(prompt).contains("mueller@jc.example")
    }

    @Test
    fun `the chat grounding has no such section for a document without a deadline`() = runTest {
        val documents = FakeDocumentRepository()
        documents.seed(testDocument(id = "d1"))
        documents.seedExtracted("d1", field("sender", "Sender", "Stadtwerke Beispielstadt"))

        val prompt = BuildChatContextUseCase(documents, FakeProfileRepository(), letterContactsFor()).invoke("d1", contextTokens = 4096).text

        assertThat(prompt).doesNotContain("What was read from this letter")
    }
}
