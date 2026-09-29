package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExtractedPresenterTest {

    private var n = 0

    private fun field(
        name: String,
        value: String = "v",
        slotKey: String? = null,
        type: ExtractedFieldType = ExtractedFieldType.OTHER,
        confidence: Float = 0.9f,
        source: ValueSource = ValueSource.MACHINE,
        confirmed: Boolean = false,
        deleted: Boolean = false,
    ) = ExtractedData(
        id = "f${n++}", documentId = "d", fieldName = name, fieldValue = value, fieldType = type,
        confidence = confidence, slotKey = slotKey, source = source, isConfirmed = confirmed, deletedByUser = deleted,
    )

    private fun extra(label: String, confidence: Float = 0.9f, source: ValueSource = ValueSource.MACHINE, confirmed: Boolean = false) =
        field(label, "x", slotKey = "x:" + label.lowercase().replace(' ', '_'), confidence = confidence, source = source, confirmed = confirmed)

    private fun doc(type: String? = "bill", summary: String? = null) = Document(
        id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        extractionType = type, summary = summary,
    )

    private fun present(fields: List<ExtractedData>, type: String? = "bill", summary: String? = null, showAll: Boolean = false) =
        ExtractedPresenter.present(doc(type, summary), fields, showAll)

    @Nested
    @DisplayName("Summary card")
    inner class Card {

        @Test
        fun `from, for, type, amount, due and the AI summary are read from the slots`() {
            val p = present(
                listOf(
                    field("Sender Organization", "Nordlicht Mobilfunk GmbH", "sender"),
                    field("Receiver Name", "Erika Mustermann", "addressee"),
                    field("Amount", "64,98 €", "total"),
                    field("Deadline", "15.10.2026", "due_date"),
                ),
                type = "reminder_dunning",
                summary = "Pay 64,98 € within 14 days.",
            )

            assertThat(p.summary.from!!.value).isEqualTo("Nordlicht Mobilfunk GmbH")
            assertThat(p.summary.forWhom!!.value).isEqualTo("Erika Mustermann")
            assertThat(p.summary.typeId).isEqualTo("reminder_dunning")
            assertThat(p.summary.amount!!.value).isEqualTo("64,98 €")
            assertThat(p.summary.due!!.value).isEqualTo("15.10.2026")
            assertThat(p.summary.aiSummary).isEqualTo("Pay 64,98 € within 14 days.")
        }

        @Test
        fun `a line the checks flagged is marked worth checking, a confident one is not`() {
            val p = present(
                listOf(
                    field("Amount", "64,98 €", "total", confidence = 0.4f),
                    field("Deadline", "15.10.2026", "due_date", confidence = 0.9f),
                ),
            )

            assertThat(p.summary.amount!!.worthChecking).isTrue()
            assertThat(p.summary.due!!.worthChecking).isFalse()
        }

        @Test
        fun `the main amount is the first present of the type's amount slots, the objection deadline stands in for a due date`() {
            val p = present(
                listOf(
                    field("New Amount", "612,40 €", "new_amount"),
                    field("Amount Paid", "1,00 €", "proof_amount"),
                    field("Objection Deadline", "01.11.2026", "objection_deadline"),
                ),
                type = "insurance_contract",
            )

            assertThat(p.summary.amount!!.value).isEqualTo("612,40 €")
            assertThat(p.summary.due!!.value).isEqualTo("01.11.2026")
        }

        @Test
        fun `rows stored before slot keys still fill the card by their old names`() {
            val p = present(
                listOf(
                    field("Sender Name", "Jobcenter"),
                    field("Receiver Name", "Erika"),
                    field("Amount", "10 €"),
                    field("Deadline", "01.01.2027"),
                ),
                type = null,
            )

            assertThat(p.summary.from!!.value).isEqualTo("Jobcenter")
            assertThat(p.summary.forWhom!!.value).isEqualTo("Erika")
            assertThat(p.summary.amount!!.value).isEqualTo("10 €")
            assertThat(p.summary.due!!.value).isEqualTo("01.01.2027")
        }

        @Test
        fun `a user's edit is what the card shows, and a deleted field is not shown`() {
            val p = present(
                listOf(
                    field("Amount", "70,00 €", "total", source = ValueSource.USER, confirmed = true),
                    field("Deadline", "15.10.2026", "due_date", deleted = true),
                ),
            )

            assertThat(p.summary.amount!!.value).isEqualTo("70,00 €")
            assertThat(p.summary.due).isNull()
        }

        @Test
        fun `a document nothing was read for has an empty card`() {
            assertThat(present(emptyList(), type = null).summary.isEmpty).isTrue()
        }
    }

    @Nested
    @DisplayName("Details grouping")
    inner class Grouping {

        @Test
        fun `fields are grouped by what they are, in a fixed group order`() {
            val p = present(
                listOf(
                    field("Invoice Number", "R-1", "invoice_no"),
                    field("IBAN", "DE89", "iban"),
                    field("Deadline", "15.10.2026", "due_date"),
                    field("Sender Name", "Nordlicht", "sender"),
                    field("Subject", "Zahlungserinnerung", "subject"),
                    field("Amount", "1 €", "total"),
                ),
            )

            assertThat(p.details.map { it.group }).containsExactly(
                DetailGroup.PARTIES, DetailGroup.MONEY, DetailGroup.DATES, DetailGroup.REFERENCES, DetailGroup.TEXT,
            ).inOrder()
            assertThat(p.details.single { it.group == DetailGroup.MONEY }.fields.map { it.slotKey })
                .containsExactly("total", "iban").inOrder()
        }

        @Test
        fun `within a group the type's slot order is kept, people first`() {
            val p = present(
                listOf(
                    field("IBAN", "DE89", "iban"),
                    field("Amount", "1 €", "total"),
                    field("Fee", "5 €", "fee"),
                    field("Receiver Name", "E", "addressee"),
                    field("Sender Name", "N", "sender"),
                ),
                type = "reminder_dunning",
            )

            assertThat(p.details.single { it.group == DetailGroup.PARTIES }.fields.map { it.slotKey })
                .containsExactly("sender", "addressee").inOrder()
            assertThat(p.details.single { it.group == DetailGroup.MONEY }.fields.map { it.slotKey })
                .containsExactly("total", "iban", "fee").inOrder()
        }

        @Test
        fun `a row without a slot is grouped by its type, and a plain note goes to text`() {
            val p = present(
                listOf(
                    field("Phone", "0123", type = ExtractedFieldType.PHONE),
                    field("Date", "1.1.2026", type = ExtractedFieldType.DATE),
                    field("Reference Number", "42", type = ExtractedFieldType.REFERENCE_NUMBER),
                    field("Note", "call back", type = ExtractedFieldType.TEXT),
                ),
            )

            assertThat(p.details.associate { it.group to it.fields.map { f -> f.fieldName } }).containsExactly(
                DetailGroup.PARTIES, listOf("Phone"),
                DetailGroup.DATES, listOf("Date"),
                DetailGroup.REFERENCES, listOf("Reference Number"),
                DetailGroup.TEXT, listOf("Note"),
            )
        }

        @Test
        fun `empty groups are left out`() {
            val p = present(listOf(field("Amount", "1 €", "total")))
            assertThat(p.details.map { it.group }).containsExactly(DetailGroup.MONEY)
        }
    }

    @Nested
    @DisplayName("Other details")
    inner class Extras {

        @Test
        fun `extras are kept out of Details and keep the AI's label`() {
            val p = present(listOf(field("Amount", "1 €", "total"), extra("Zaehlernummer"), extra("Tarif")))

            assertThat(p.details.flatMap { it.fields }.map { it.fieldName }).containsExactly("Amount")
            assertThat(p.extras.map { it.fieldName }).containsExactly("Zaehlernummer", "Tarif").inOrder()
            assertThat(p.hiddenExtras).isEqualTo(0)
            assertThat(p.extraCount).isEqualTo(2)
        }

        @Test
        fun `low-confidence extras are hidden behind Show all and counted`() {
            val fields = listOf(extra("Sure", 0.9f), extra("Unsure", 0.4f), extra("Fuzzy quote", 0.45f))

            val collapsed = present(fields)
            assertThat(collapsed.extras.map { it.fieldName }).containsExactly("Sure")
            assertThat(collapsed.hiddenExtras).isEqualTo(2)
            assertThat(collapsed.extraCount).isEqualTo(3)

            val all = present(fields, showAll = true)
            assertThat(all.extras.map { it.fieldName }).containsExactly("Sure", "Unsure", "Fuzzy quote").inOrder()
            assertThat(all.hiddenExtras).isEqualTo(0)
        }

        @Test
        fun `an extra the user owns or confirmed is never hidden`() {
            val p = present(
                listOf(
                    extra("Mine", 0.2f, source = ValueSource.USER),
                    extra("Confirmed", 0.2f, confirmed = true),
                ),
            )

            assertThat(p.extras.map { it.fieldName }).containsExactly("Mine", "Confirmed")
            assertThat(p.hiddenExtras).isEqualTo(0)
        }

        @Test
        fun `a deleted extra is not counted`() {
            val p = present(listOf(extra("Gone").copy(deletedByUser = true)))

            assertThat(p.extraCount).isEqualTo(0)
        }
    }
}
