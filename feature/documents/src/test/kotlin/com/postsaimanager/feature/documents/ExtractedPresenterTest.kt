package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SectionKind
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
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

    private fun extra(label: String, confidence: Float = 0.9f, source: ValueSource = ValueSource.MACHINE, confirmed: Boolean = false, value: String = "x") =
        field(label, value, slotKey = "x:" + label.lowercase().replace(' ', '_'), confidence = confidence, source = source, confirmed = confirmed)

    /** A stored address part: `addressee.street`, `sender.city`, ... */
    private fun part(prefix: String, key: String, value: String, confidence: Float = 0.9f, confirmed: Boolean = false, deleted: Boolean = false) =
        field("$prefix $key", value, slotKey = "$prefix.$key", type = ExtractedFieldType.ADDRESS, confidence = confidence, confirmed = confirmed, deleted = deleted)

    private fun doc(
        type: String? = "bill",
        summary: String? = null,
        titleCode: String? = null,
        titleArgs: List<String> = emptyList(),
        summarySource: SummarySource? = null,
        summaryCode: String? = null,
        summaryArgs: List<String> = emptyList(),
        topics: List<String> = emptyList(),
        typeConfidence: Float? = null,
        familySource: FamilySource = FamilySource.MODEL,
        actionItems: List<ActionItem> = emptyList(),
        extractorVersion: String? = ExtractorVersion.CURRENT,
    ) = Document(
        id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        extractionType = type, summary = summary, titleCode = titleCode, titleArgs = titleArgs, summarySource = summarySource,
        summaryCode = summaryCode, summaryArgs = summaryArgs, topics = topics, extractionTypeConfidence = typeConfidence,
        familySource = familySource, actionItems = actionItems, extractorVersion = extractorVersion,
    )

    private fun present(
        fields: List<ExtractedData>,
        type: String? = "bill",
        summary: String? = null,
        showAll: Boolean = false,
        selfName: String? = null,
        actions: List<ActionItem> = emptyList(),
        version: String? = ExtractorVersion.CURRENT,
    ) = ExtractedPresenter.present(doc(type, summary, actionItems = actions, extractorVersion = version), fields, showAll, selfName = selfName)

    private fun ExtractedPresentation.kinds() = sections.map { it.kind }

    private fun ExtractedPresentation.slotsIn(kind: SectionKind): List<String?> =
        sections.single { it.kind == kind }.items.flatMap { it.rows }.map { it.slotKey }

    private fun ExtractedPresentation.detailSlots(): List<String?> = sections.flatMap { it.items }.flatMap { it.rows }.map { it.slotKey }

    @Nested
    @DisplayName("Summary coming")
    inner class SummaryComing {

        @Test
        fun `the card says the summary is coming while the second stage is pending and there is none yet`() {
            val pending = ExtractedPresenter.present(doc(summary = null), listOf(field("Amount", "64,98 €", "total")), summaryComing = true)
            assertThat(pending.summary.summaryComing).isTrue()
            assertThat(pending.summary.hasSummary).isFalse()
        }

        @Test
        fun `a summary that has landed is shown and nothing is coming any more`() {
            val landed = ExtractedPresenter.present(doc(summary = "Pay 64,98 €."), listOf(field("Amount", "64,98 €", "total")), summaryComing = true)
            assertThat(landed.summary.summaryComing).isFalse()
            assertThat(landed.summary.summaryText).isEqualTo("Pay 64,98 €.")
        }

        @Test
        fun `a template summary that has landed also ends the wait`() {
            val d = doc(summary = null, summaryCode = "template", summaryArgs = listOf("invoice_bill", "", "", "", "", ""), summarySource = SummarySource.TEMPLATE)
            assertThat(ExtractedPresenter.present(d, emptyList(), summaryComing = true).summary.summaryComing).isFalse()
        }

        @Test
        fun `nothing is coming when no second stage is pending`() {
            val none = ExtractedPresenter.present(doc(summary = null), listOf(field("Amount", "64,98 €", "total")))
            assertThat(none.summary.summaryComing).isFalse()
        }
    }

    @Nested
    @DisplayName("Summary card")
    inner class Card {

        @Test
        fun `the card holds the title and the summary only, the rest is in the essentials`() {
            val p = present(
                listOf(
                    field("Sender Organization", "Nordlicht Mobilfunk GmbH", "sender"),
                    field("Amount", "64,98 €", "total"),
                ),
                type = "reminder_dunning",
                summary = "Pay 64,98 € within 14 days.",
            )

            assertThat(p.summary.summaryText).isEqualTo("Pay 64,98 € within 14 days.")
            assertThat(p.essentials.parties.from!!.row.fieldValue).isEqualTo("Nordlicht Mobilfunk GmbH")
        }

        @Test
        fun `a document nothing was read for has an empty card`() {
            assertThat(present(emptyList(), type = null).summary.isEmpty).isTrue()
        }

        @Test
        fun `a composed title hands its args to the card, any other title does not`() {
            val composed = ExtractedPresenter.present(doc(titleCode = "composed", titleArgs = listOf("invoice_bill", "Telekom", "Rechnung")), emptyList())
            assertThat(composed.summary.titleArgs).containsExactly("invoice_bill", "Telekom", "Rechnung").inOrder()
            assertThat(ExtractedPresenter.present(doc(titleCode = "scanned_pages", titleArgs = listOf("2")), emptyList()).summary.titleArgs).isNull()
            assertThat(ExtractedPresenter.present(doc(), emptyList()).summary.titleArgs).isNull()
        }

        @Test
        fun `the badge follows the summary source, and a stored summary with no source reads as the model's`() {
            fun source(d: Document) = ExtractedPresenter.present(d, emptyList()).summary.summarySource
            assertThat(source(doc(summary = "S", summarySource = SummarySource.MODEL))).isEqualTo(SummarySource.MODEL)
            assertThat(source(doc(summary = "S", summarySource = SummarySource.USER))).isEqualTo(SummarySource.USER)
            assertThat(source(doc(summary = "S"))).isEqualTo(SummarySource.MODEL)
            assertThat(source(doc())).isNull()
        }

        @Test
        fun `a template summary hands its args to the card and is badged as from the fields`() {
            val args = listOf("invoice_bill", "Telekom", "", "64,98 €", "", "")
            val card = ExtractedPresenter.present(doc(summaryCode = "template", summaryArgs = args), emptyList()).summary
            assertThat(card.summaryText).isNull()
            assertThat(card.templateArgs).containsExactlyElementsIn(args).inOrder()
            assertThat(card.summarySource).isEqualTo(SummarySource.TEMPLATE)
        }

        @Test
        fun `the person's own summary wins over a template one left in the args`() {
            val d = doc(summary = "Mine", summarySource = SummarySource.USER, summaryCode = "template", summaryArgs = listOf("invoice_bill"))
            val card = ExtractedPresenter.present(d, emptyList()).summary
            assertThat(card.summaryText).isEqualTo("Mine")
            assertThat(card.templateArgs).isNull()
        }
    }

    @Nested
    @DisplayName("Header")
    inner class HeaderTests {

        @Test
        fun `the header carries the stored type, the topics and a confidence level`() {
            val h = { c: Float? -> ExtractedPresenter.present(doc("invoice_bill", topics = listOf("telecom", "shopping"), typeConfidence = c), emptyList()).header }
            assertThat(h(0.9f).typeId).isEqualTo("invoice_bill")
            assertThat(h(0.9f).topics).containsExactly("telecom", "shopping").inOrder()
            assertThat(h(0.9f).confidence).isEqualTo(Confidence.HIGH)
            assertThat(h(0.6f).confidence).isEqualTo(Confidence.MEDIUM)
            assertThat(h(0.2f).confidence).isEqualTo(Confidence.LOW)
            assertThat(h(null).confidence).isNull()
        }

        @Test
        fun `a type the person chose has no confidence dot`() {
            val h = ExtractedPresenter.present(doc("receipt", typeConfidence = 0.9f, familySource = FamilySource.USER), emptyList()).header
            assertThat(h.confidence).isNull()
        }
    }

    @Nested
    @DisplayName("Essentials")
    inner class EssentialsTests {

        private val sender = { field("Sender Organization", "Nordlicht Mobilfunk GmbH", "sender") }
        private val addressee = { field("Receiver Name", "Erika Mustermann", "addressee") }

        private val invoiceFields = {
            listOf(
                sender(), addressee(),
                field("Subject", "Zahlungserinnerung", "subject"),
                field("Amount", "64,98 €", "total"),
                field("Deadline", "15.10.2026", "due_date"),
                field("IBAN", "DE89 3704 0044 0532 0130 00", "iban"),
                field("Invoice Number", "R-1", "invoice_no"),
                extra("Mandatsreferenz", value = "M-2026-77"),
                extra("Tarif", value = "Komfort"),
            )
        }

        @Test
        fun `From is the sender, For the addressee and the subject opens the key information`() {
            val e = present(invoiceFields(), type = "invoice_bill").essentials

            assertThat(e.parties.from!!.row.fieldValue).isEqualTo("Nordlicht Mobilfunk GmbH")
            assertThat(e.parties.forWhom!!.row.fieldValue).isEqualTo("Erika Mustermann")
            assertThat(e.parties.forWhom!!.recipient).isEqualTo(PagesRecipient.Named("Erika Mustermann"))
            assertThat(e.subject!!.fieldValue).isEqualTo("Zahlungserinnerung")
        }

        @Test
        fun `For reads You on an exact folded match with the Me profile, and the name otherwise`() {
            assertThat(present(invoiceFields(), selfName = "erika  MUSTERMANN").essentials.parties.forWhom!!.recipient).isEqualTo(PagesRecipient.You)
            assertThat(present(invoiceFields(), selfName = "Erika Mustermann-Berger").essentials.parties.forWhom!!.recipient)
                .isEqualTo(PagesRecipient.Named("Erika Mustermann"))
            assertThat(present(invoiceFields(), selfName = null).essentials.parties.forWhom!!.recipient).isEqualTo(PagesRecipient.Named("Erika Mustermann"))
        }

        @Test
        fun `About is shown only for another person than the addressee`() {
            val child = field("About Person", "Mia Mustermann", "subject_person")
            val same = field("About Person", "ERIKA MUSTERMANN", "subject_person")

            assertThat(present(invoiceFields() + child).essentials.parties.about!!.row.fieldValue).isEqualTo("Mia Mustermann")
            assertThat(present(invoiceFields() + same).essentials.parties.about).isNull()
            assertThat(present(invoiceFields()).essentials.parties.about).isNull()
            // The subject person equal to the addressee is not lost: it stays in "All details".
            assertThat(present(invoiceFields() + same).detailSlots()).contains("subject_person")
            assertThat(present(invoiceFields() + child).detailSlots()).doesNotContain("subject_person")
        }

        @Test
        fun `a party's verified address is one compact line, none for You or without an address`() {
            val rows = listOf(
                sender(), addressee(),
                part("sender", "street", "Hauptstraße").copy(origin = AddressRows.ORIGIN_VERIFIED),
                part("sender", "house_number", "5").copy(origin = AddressRows.ORIGIN_VERIFIED),
                part("sender", "postcode", "10115").copy(origin = AddressRows.ORIGIN_VERIFIED),
                part("sender", "city", "Berlin").copy(origin = AddressRows.ORIGIN_VERIFIED),
                part("addressee", "street", "Gartenweg 2").copy(origin = AddressRows.ORIGIN_VERIFIED),
            )

            val named = present(rows, type = "invoice_bill").essentials.parties
            assertThat(named.from!!.addressLines).containsExactly("Hauptstraße 5", "10115 Berlin").inOrder()
            assertThat(named.forWhom!!.addressLines).containsExactly("Gartenweg 2")

            val you = present(rows, type = "invoice_bill", selfName = "Erika Mustermann").essentials.parties
            assertThat(you.forWhom!!.addressLines).isEmpty()
            assertThat(present(listOf(sender(), addressee()), type = "invoice_bill").essentials.parties.from!!.addressLines).isEmpty()
        }

        @Test
        fun `an address that did not pass its checks is never shown as a line`() {
            val rows = listOf(sender(), part("sender", "city", "Berlin").copy(origin = AddressRows.ORIGIN))
            assertThat(present(rows, type = "invoice_bill").essentials.parties.from!!.addressLines).isEmpty()
        }

        @Test
        fun `the key information is the extras the AI picked, the rest of the fields are in All details`() {
            val p = present(invoiceFields(), type = "invoice_bill")

            assertThat(p.essentials.keyInfo.map { it.fieldName }).containsExactly("Mandatsreferenz", "Tarif").inOrder()
            assertThat(p.extras).isEmpty()
            assertThat(p.detailSlots()).containsExactly("due_date", "total", "iban", "invoice_no")
            assertThat(p.detailCount).isEqualTo(4)
        }

        @Test
        fun `a row with no value is no card anywhere, not in All details, the count or Check these`() {
            val rows = invoiceFields() + field("Sender", "", "sender", confidence = 0.2f) + extra("Blank", value = "  ")
            val p = present(rows, type = "official_letter")

            assertThat(p.detailSlots()).doesNotContain("sender")
            assertThat(p.detailSlots()).doesNotContain("x:blank")
            assertThat(p.extras.map { it.fieldName }).doesNotContain("Blank")
            assertThat(p.essentials.rows.map { it.fieldName }).doesNotContain("Sender")
            assertThat(p.detailCount).isEqualTo(present(invoiceFields(), type = "official_letter").detailCount)
            assertThat(p.checkCount).isEqualTo(present(invoiceFields(), type = "official_letter").checkCount)
        }

        @Test
        fun `an empty field a person added themselves stays, so they can fill it in`() {
            val own = field("Note", "", "x:note", source = ValueSource.USER)
            val p = present(invoiceFields() + own, type = "invoice_bill")
            assertThat((p.extras + p.essentials.keyInfo).map { it.fieldName }).contains("Note")
        }

        @Test
        fun `a very unsure extra is not key information, it waits behind Show all in the details`() {
            val rows = invoiceFields() + extra("Faint", confidence = 0.3f)

            val collapsed = present(rows, type = "invoice_bill")
            assertThat(collapsed.essentials.keyInfo.map { it.fieldName }).doesNotContain("Faint")
            assertThat(collapsed.hiddenExtras).isEqualTo(1)
            assertThat(collapsed.detailCount).isEqualTo(5)
            assertThat(present(rows, type = "invoice_bill", showAll = true).essentials.keyInfo.map { it.fieldName }).contains("Faint")
        }

        @Test
        fun `a document read before the key information existed has only the parties and the subject on top`() {
            for (version in listOf("extraction-v2-2", "extraction-v2-1", null)) {
                val p = present(invoiceFields(), type = "invoice_bill", version = version)

                assertThat(p.essentials.keyInfo).isEmpty()
                assertThat(p.essentials.actions).isEmpty()
                assertThat(p.essentials.parties.from).isNotNull()
                assertThat(p.essentials.subject).isNotNull()
                assertThat(p.extras.map { it.fieldName }).containsExactly("Mandatsreferenz", "Tarif").inOrder()
                assertThat(p.detailSlots()).containsExactly("due_date", "total", "iban", "invoice_no")
            }
        }

        @Test
        fun `the actions are the kinds the AI chose, each with the live fields it states`() {
            val actions = listOf(
                ActionItem("pay", mapOf("amount" to "total", "date" to "due_date", "party" to "sender")),
                ActionItem("reply", mapOf("party" to "sender", "reference" to "invoice_no")),
            )
            val p = present(invoiceFields(), type = "invoice_bill", actions = actions)

            assertThat(p.essentials.actions.map { it.kind.id }).containsExactly("pay", "reply").inOrder()
            assertThat(p.essentials.actions[0].amount).isEqualTo("64,98 €")
            assertThat(p.essentials.actions[0].party).isEqualTo("Nordlicht Mobilfunk GmbH")
            assertThat(p.essentials.actions[0].rows.map { it.slotKey }).containsExactly("due_date", "total").inOrder()
            assertThat(p.essentials.actions[1].rows.map { it.slotKey }).containsExactly("invoice_no")
            // The fields behind an action are not listed again below it.
            assertThat(p.detailSlots()).containsExactly("iban")
        }

        /** The invoice fields with the invoice number and the IBAN picked as key information (the IBAN the better). */
        private fun pickedFields() = invoiceFields().map {
            when (it.slotKey) {
                "invoice_no" -> it.copy(importance = 1.5f)
                "iban" -> it.copy(importance = 3f)
                else -> it
            }
        }

        @Test
        fun `the slot rows the AI picked are key information after the subject, best score first, then the extras`() {
            val p = present(pickedFields(), type = "invoice_bill")

            assertThat(p.essentials.subject!!.fieldValue).isEqualTo("Zahlungserinnerung")
            assertThat(p.essentials.keyInfo.map { it.fieldName }).containsExactly("IBAN", "Invoice Number", "Mandatsreferenz", "Tarif").inOrder()
            // They are not in "All details" as well.
            assertThat(p.detailSlots()).containsExactly("due_date", "total")
        }

        @Test
        fun `however many rows are stored as picked, the key information is at most eight, the rest are in the lists below`() {
            val many = pickedFields() + (1..6).map { extra("Extra $it") }
            val p = present(many, type = "invoice_bill")

            assertThat(p.essentials.keyInfo).hasSize(com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions.MAX_KEY_INFO_SHOWN)
            // The best slots come first; what did not fit is still shown, under "All details", never lost.
            assertThat(p.essentials.keyInfo.map { it.fieldName }.take(2)).containsExactly("IBAN", "Invoice Number").inOrder()
            assertThat(p.extras.map { it.fieldName }).isNotEmpty()
        }

        @Test
        fun `a picked slot row an action line already states stays in that line, not drawn twice`() {
            val p = present(pickedFields(), type = "invoice_bill", actions = listOf(ActionItem("pay", mapOf("iban" to "iban"))))

            assertThat(p.essentials.actions.single().rows.map { it.slotKey }).containsExactly("iban")
            assertThat(p.essentials.keyInfo.map { it.fieldName }).containsExactly("Invoice Number", "Mandatsreferenz", "Tarif").inOrder()
            assertThat(p.essentials.rows.map { it.id }).containsNoDuplicates()
        }

        @Test
        fun `Check these counts an unsure picked slot row, and one nobody picked stays in All details`() {
            val rows = pickedFields().map { if (it.slotKey == "invoice_no") it.copy(confidence = 0.4f) else if (it.slotKey == "total") it.copy(confidence = 0.4f) else it }
            val p = present(rows, type = "invoice_bill")

            assertThat(p.checkCount).isEqualTo(1)
            assertThat(p.review.uncertain).isEqualTo(1)
            assertThat(p.detailSlots()).contains("total")
        }

        @Test
        fun `a picked slot row is key information only for a document read by a version that picks them`() {
            val p = present(pickedFields(), type = "invoice_bill", version = "extraction-v2-2")

            assertThat(p.essentials.keyInfo).isEmpty()
            assertThat(p.detailSlots()).containsAtLeast("iban", "invoice_no")
        }

        @Test
        fun `a document with no action lines has no actions, whatever fields it holds`() {
            val p = present(invoiceFields(), type = "invoice_bill", actions = emptyList())
            assertThat(p.essentials.actions).isEmpty()
        }

        @Test
        fun `a value a person corrected is the value the action states at once, there is no stale line`() {
            val edited = field("Amount", "70,00 €", "total", source = ValueSource.USER, confirmed = true).copy(machineValue = "64,98 €")
            val rows = invoiceFields().filter { it.slotKey != "total" } + edited
            val p = present(rows, type = "invoice_bill", actions = listOf(ActionItem("pay", mapOf("amount" to "total", "date" to "due_date"))))

            assertThat(p.essentials.actions.single().amount).isEqualTo("70,00 €")
            assertThat(p.essentials.actions.single().rows.map { it.slotKey }).contains("total")
        }

        @Test
        fun `an ignored field is not behind an action, which is then shorter`() {
            val rows = invoiceFields().map { if (it.slotKey == "iban") it.copy(deletedByUser = true) else it }
            val p = present(rows, type = "invoice_bill", actions = listOf(ActionItem("pay", mapOf("iban" to "iban"))))
            assertThat(p.essentials.actions.single().rows).isEmpty()
            assertThat(p.ignored.map { it.slotKey }).containsExactly("iban")
        }

        @Test
        fun `every family presents the same essentials, they are the AI's data and not a list per family`() {
            val lines = listOf(ActionItem("pay", mapOf("amount" to "total")))
            val reference = present(invoiceFields(), type = "invoice_bill", actions = lines).essentials
            for (family in ExtractionSchema.DEFAULT.families.map { it.id } + listOf("reminder_dunning", "mystery", null)) {
                val e = present(invoiceFields(), type = family, actions = lines).essentials
                assertThat(e.parties.from!!.row.fieldValue).isEqualTo(reference.parties.from!!.row.fieldValue)
                assertThat(e.actions.map { it.amount }).isEqualTo(reference.actions.map { it.amount })
                assertThat(e.keyInfo.map { it.fieldName }).isEqualTo(reference.keyInfo.map { it.fieldName })
                assertThat(e.subject!!.fieldValue).isEqualTo("Zahlungserinnerung")
            }
        }

        @Test
        fun `rows stored before slot keys still give the sender and the addressee`() {
            val p = present(listOf(field("Sender Name", "Jobcenter"), field("Receiver Name", "Erika"), field("Amount", "10 €")), type = null)
            assertThat(p.essentials.parties.from!!.row.fieldValue).isEqualTo("Jobcenter")
            assertThat(p.essentials.parties.forWhom!!.row.fieldValue).isEqualTo("Erika")
            assertThat(p.detailCount).isEqualTo(1)
        }

        @Test
        fun `a document with no party and no subject has no parties block`() {
            val p = present(listOf(field("Amount", "10 €", "total")))
            assertThat(p.essentials.parties.isEmpty).isTrue()
            assertThat(p.essentials.subject).isNull()
        }
    }

    @Nested
    @DisplayName("Sections per family")
    inner class Families {

        private val letterFields = {
            listOf(
                field("Receiver Name", "Erika Mustermann", "addressee"),
                part("addressee", "city", "Berlin"),
                field("Sender Name", "Nordlicht", "sender"),
                part("sender", "city", "Hamburg"),
                field("Contact Person", "Frau Müller", "contact"),
                field("Deadline", "15.10.2026", "due_date"),
                field("Amount", "64,98 €", "total"),
                field("IBAN", "DE89", "iban"),
                field("Invoice Number", "R-1", "invoice_no"),
                field("Customer Number", "K-9", "customer_no"),
                field("Document Date", "01.10.2026", "letter_date"),
                field("Original Due Date", "01.09.2026", "original_due_date"),
            )
        }

        @Test
        fun `a letter-like family keeps the address blocks, the text, the action, references and dates in All details`() {
            for (family in listOf("official_letter", "invoice_bill", "statement", "contract_policy", "medical")) {
                val p = present(letterFields(), type = family)
                assertThat(p.kinds()).containsExactly(
                    SectionKind.RECIPIENT_BLOCK, SectionKind.SENDER_BLOCK, SectionKind.TEXT, SectionKind.ACTION,
                    SectionKind.REFERENCES, SectionKind.DATES,
                ).inOrder()
            }
        }

        @Test
        fun `within a letter's sections the spec's slot order is kept`() {
            val p = present(letterFields(), type = "invoice_bill")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("due_date", "total", "iban").inOrder()
            assertThat(p.slotsIn(SectionKind.REFERENCES)).containsExactly("invoice_no", "customer_no").inOrder()
            assertThat(p.slotsIn(SectionKind.DATES)).containsExactly("letter_date", "original_due_date").inOrder()
        }

        @Test
        fun `a legacy type id is presented as the family it stands for`() {
            val legacy = present(letterFields(), type = "reminder_dunning")
            assertThat(legacy.kinds()).isEqualTo(present(letterFields(), type = "invoice_bill").kinds())
            assertThat(present(letterFields(), type = "authority_tax").kinds().first()).isEqualTo(SectionKind.RECIPIENT_BLOCK)
        }

        @Test
        fun `a topic slot the spec does not name is placed by its kind`() {
            val p = present(
                listOf(field("Payment Reference", "ZV-1", "proof_reference"), field("Sent Date", "02.11.2026", "sent_date"), field("Amount Paid", "1 €", "proof_amount")),
                type = "official_letter",
            )
            assertThat(p.slotsIn(SectionKind.REFERENCES)).containsExactly("proof_reference")
            assertThat(p.slotsIn(SectionKind.DATES)).containsExactly("sent_date")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("proof_amount")
        }

        @Test
        fun `a receipt keeps its payment, dates and references in All details`() {
            val p = present(
                listOf(
                    field("Receipt Number", "B-77", "receipt_no"),
                    field("Document Date", "01.10.2026", "letter_date"),
                    field("Amount", "12,50 €", "total"),
                    field("IBAN", "DE89", "iban"),
                    field("Sender Name", "Bäckerei Korn", "sender"),
                ),
                type = "receipt",
            )

            assertThat(p.essentials.parties.from!!.row.fieldValue).isEqualTo("Bäckerei Korn")
            assertThat(p.kinds()).containsExactly(SectionKind.PAYMENT, SectionKind.DATES, SectionKind.REFERENCES).inOrder()
            assertThat(p.slotsIn(SectionKind.PAYMENT)).containsExactly("total", "iban").inOrder()
        }

        @Test
        fun `free form keeps the people, text and the grouping of before`() {
            val p = present(
                listOf(
                    field("Phone", "0123", type = ExtractedFieldType.PHONE),
                    field("Date", "1.1.2026", type = ExtractedFieldType.DATE),
                    field("Reference Number", "42", type = ExtractedFieldType.REFERENCE_NUMBER),
                    field("Note", "call back", type = ExtractedFieldType.TEXT),
                    field("Amount", "1 €", "total"),
                ),
                type = "free_form",
            )

            assertThat(p.kinds()).containsExactly(
                SectionKind.PARTIES, SectionKind.TEXT, SectionKind.ACTION, SectionKind.DATES, SectionKind.REFERENCES,
            ).inOrder()
            assertThat(p.sections.single { it.kind == SectionKind.TEXT }.items.single().rows.single().fieldName).isEqualTo("Note")
        }

        @Test
        fun `an unknown or missing family is presented as free form, and a family with no address block draws no block`() {
            val rows = listOf(field("Sender Name", "N", "sender"), part("sender", "street", "Hauptstr."))
            assertThat(present(rows, type = "mystery").sections.none { it.items.any { i -> i is AddressBlock } }).isTrue()
            assertThat(present(rows, type = null).sections.none { it.items.any { i -> i is AddressBlock } }).isTrue()
            assertThat(present(rows, type = "invoice_bill").sections.any { it.items.any { i -> i is AddressBlock } }).isTrue()
        }

        @Test
        fun `empty sections are left out`() {
            val p = present(listOf(field("Amount", "1 €", "total")), type = "invoice_bill")
            assertThat(p.kinds()).containsExactly(SectionKind.ACTION)
        }

        @Test
        fun `changing the family re-presents the same rows under the new layout`() {
            val rows = listOf(field("Sender Name", "Korn", "sender"), part("sender", "city", "Berlin"), field("Amount", "1 €", "total"))
            assertThat(present(rows, type = "invoice_bill").kinds()).containsExactly(SectionKind.SENDER_BLOCK, SectionKind.ACTION).inOrder()
            // A receipt has no address block: the sender's address parts are plain rows under the people.
            assertThat(present(rows, type = "receipt").kinds()).containsExactly(SectionKind.PAYMENT, SectionKind.PARTIES).inOrder()
        }
    }

    @Nested
    @DisplayName("Address blocks")
    inner class Blocks {

        private fun block(p: ExtractedPresentation, role: PartyRole): AddressBlock =
            p.sections.flatMap { it.items }.filterIsInstance<AddressBlock>().single { it.role == role }

        private fun AddressBlock.lineValues() = lines.map { l -> l.parts.map { it.fieldValue } }

        @Test
        fun `the block is the parts in print order, street with number and postcode with city on one line, the name is the From or For line`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika Mustermann", "addressee"),
                    part("addressee", "country", "DE"),
                    part("addressee", "city", "Berlin"),
                    part("addressee", "postcode", "10115"),
                    part("addressee", "house_number", "12"),
                    part("addressee", "street", "Hauptstraße"),
                    part("addressee", "organisation", "Mustermann GmbH"),
                    part("addressee", "raw", "Erika Mustermann\nHauptstraße 12\n10115 Berlin"),
                ),
                type = "invoice_bill",
            )

            val b = block(p, PartyRole.ADDRESSEE)
            assertThat(b.nameRow).isNull()
            assertThat(b.lineValues()).containsExactly(
                listOf("Mustermann GmbH"), listOf("Hauptstraße", "12"), listOf("10115", "Berlin"), listOf("DE"),
            ).inOrder()
            // The raw lines are kept for the actions but not drawn while parts exist.
            assertThat(b.shownRows.map { it.slotKey }).doesNotContain("addressee.raw")
            assertThat(b.rows.map { it.slotKey }).contains("addressee.raw")
            assertThat(b.rows.map { it.slotKey }).doesNotContain("addressee")
            assertThat(p.essentials.parties.forWhom!!.addressLines)
                .containsExactly("Mustermann GmbH", "Hauptstraße 12", "10115 Berlin", "DE").inOrder()
        }

        @Test
        fun `a stored block of an unverified address is hidden, the party name stays, and a verified block shows`() {
            fun rows(origin: String) = listOf(
                field("Receiver Name", "Erika Mustermann", "addressee"),
                part("addressee", "city", "Berlin").copy(origin = origin),
                part("addressee", "raw", "Rechnung Nr. 1\nBerlin").copy(origin = origin),
            )

            val hidden = present(rows(AddressRows.ORIGIN), type = "invoice_bill")
            assertThat(hidden.essentials.parties.forWhom!!.row.fieldValue).isEqualTo("Erika Mustermann")
            assertThat(hidden.sections.flatMap { it.items }.filterIsInstance<AddressBlock>()).isEmpty()

            val verified = block(present(rows(AddressRows.ORIGIN_VERIFIED), type = "invoice_bill"), PartyRole.ADDRESSEE)
            assertThat(verified.lineValues()).containsExactly(listOf("Berlin"))
        }

        @Test
        fun `a packstation address has a locker line in place of street and number`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee"),
                    part("addressee", "packstation", "Packstation 123"),
                    part("addressee", "postcode", "10115"),
                    part("addressee", "city", "Berlin"),
                ),
                type = "official_letter",
            )

            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Packstation 123"), listOf("10115", "Berlin")).inOrder()
        }

        @Test
        fun `a post office box address has its own line`() {
            val p = present(
                listOf(
                    part("sender", "organisation", "Finanzamt"),
                    part("sender", "po_box", "Postfach 12 34"),
                    part("sender", "postcode", "10115"),
                    part("sender", "city", "Berlin"),
                ),
                type = "official_letter",
            )

            val b = block(p, PartyRole.SENDER)
            assertThat(b.nameRow).isNull()
            assertThat(b.lineValues()).containsExactly(listOf("Finanzamt"), listOf("Postfach 12 34"), listOf("10115", "Berlin")).inOrder()
        }

        @Test
        fun `a recipient name that repeats the name row is not drawn twice`() {
            val p = present(
                listOf(field("Receiver Name", "Erika Mustermann", "addressee"), part("addressee", "name", "erika mustermann"), part("addressee", "city", "Berlin")),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Berlin"))
            assertThat(p.essentials.parties.forWhom!!.addressLines).containsExactly("Berlin")
        }

        @Test
        fun `a block with no part read draws the printed lines instead`() {
            val p = present(
                listOf(field("Sender Name", "Korn", "sender"), part("sender", "raw", "Korn\nIrgendwo")),
                type = "invoice_bill",
            )
            val b = block(p, PartyRole.SENDER)
            assertThat(b.lines).isEmpty()
            assertThat(b.shownRows.map { it.slotKey }).containsExactly("sender.raw")
        }

        @Test
        fun `an uncertain part stays in the block of All details and is not counted among the essentials`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee"),
                    part("addressee", "street", "Hauptstr.", confidence = 0.4f),
                    part("addressee", "city", "Berlin", confidence = 0.5f),
                    part("addressee", "country", "DE"),
                ),
                type = "invoice_bill",
            )

            assertThat(block(p, PartyRole.ADDRESSEE).uncertainRows).hasSize(2)
            assertThat(p.checkCount).isEqualTo(0)
            assertThat(p.review.uncertain).isEqualTo(0)
        }

        @Test
        fun `a block with every part confirmed is settled and one line`() {
            val p = present(
                listOf(
                    field("Receiver Name", "Erika", "addressee", confirmed = true),
                    part("addressee", "city", "Berlin", confirmed = true),
                ),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).isSettled).isTrue()
        }

        @Test
        fun `an ignored part leaves the block and goes to the footer`() {
            val p = present(
                listOf(field("Receiver Name", "Erika", "addressee"), part("addressee", "street", "Falsch", deleted = true), part("addressee", "city", "Berlin")),
                type = "invoice_bill",
            )
            assertThat(block(p, PartyRole.ADDRESSEE).lineValues()).containsExactly(listOf("Berlin"))
            assertThat(p.ignored.map { it.slotKey }).containsExactly("addressee.street")
        }
    }

    @Nested
    @DisplayName("Check these")
    inner class CheckThese {

        @Test
        fun `only uncertain essential lines are counted, the parties, the subject, the key information and the fields behind an action`() {
            val p = present(
                listOf(
                    field("Sender Organization", "N", "sender", confidence = 0.4f),
                    field("Receiver Name", "E", "addressee", confidence = 0.9f),
                    field("Subject", "S", "subject", confidence = 0.5f),
                    field("Amount", "64,98 €", "total", confidence = 0.3f),
                    field("Deadline", "15.10.2026", "due_date", confidence = 0.9f),
                    field("Invoice Number", "R-1", "invoice_no", confidence = 0.6f),
                    extra("Tarif", 0.6f),
                    extra("Zaehler", 0.9f),
                ),
                type = "invoice_bill",
                actions = listOf(ActionItem("pay", mapOf("amount" to "total", "date" to "due_date"))),
            )

            // sender, subject, the amount behind the action, the uncertain extra. The invoice number is not essential: it stays in
            // All details with its badge and is not counted.
            assertThat(p.checkCount).isEqualTo(4)
            assertThat(p.essentials.rows.count { it.isUncertain }).isEqualTo(4)
            assertThat(p.detailSlots()).containsExactly("invoice_no")
            assertThat(p.sections.single { it.kind == SectionKind.REFERENCES }.items.single().uncertainRows).hasSize(1)
        }

        @Test
        fun `an uncertain field of All details is quietly left there, with no check group`() {
            val p = present(
                listOf(field("Invoice Number", "R-1", "invoice_no", confidence = 0.4f), field("IBAN", "DE89", "iban", confidence = 0.5f)),
                type = "invoice_bill",
            )
            assertThat(p.checkCount).isEqualTo(0)
            assertThat(p.detailSlots()).containsExactly("iban", "invoice_no")
        }

        @Test
        fun `a value that changed under a person's confirmation is checked even though it is theirs`() {
            val changed = field("Subject", "Neu", "subject", source = ValueSource.USER, confirmed = true)
                .copy(hasUnreviewedMachineChange = true, machineValue = "Alt")
            val p = present(listOf(changed), type = "invoice_bill")
            assertThat(p.checkCount).isEqualTo(1)
        }

        @Test
        fun `a very unsure extra behind Show all is not counted until it is shown`() {
            val rows = listOf(field("Subject", "S", "subject", confidence = 0.4f), extra("Faint", confidence = 0.2f))
            assertThat(present(rows).checkCount).isEqualTo(1)
            assertThat(present(rows, showAll = true).checkCount).isEqualTo(2)
        }

        @Test
        fun `nothing uncertain means no check count`() {
            assertThat(present(listOf(field("Amount", "1 €", "total"))).checkCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("The review button")
    inner class ReviewButton {

        @Test
        fun `while uncertain essential lines remain it confirms the confident ones, by id, with their count`() {
            val rows = listOf(
                field("Sender Organization", "N", "sender", confidence = 0.4f),
                field("Receiver Name", "E", "addressee"),
                field("Subject", "S", "subject"),
                field("Deadline", "1.1.2027", "due_date", confirmed = true),
                field("IBAN", "DE89", "iban"),
            )
            val p = present(rows)

            assertThat(p.review.mode).isEqualTo(ConfirmMode.CONFIDENT)
            assertThat(p.review.confidentOpen).isEqualTo(2)
            assertThat(p.review.confidentIds).containsExactly(rows[1].id, rows[2].id)
            assertThat(p.review.uncertain).isEqualTo(1)
        }

        @Test
        fun `the fields of All details are never in the review button's ids`() {
            val rows = listOf(field("Receiver Name", "E", "addressee"), field("IBAN", "DE89", "iban"), field("Reference", "R", "reference"))
            val p = present(rows)
            assertThat(p.review.open).isEqualTo(1)
            assertThat(p.review.openIds).containsExactly(rows[0].id)
        }

        @Test
        fun `once nothing essential is uncertain it confirms all of the essential lines`() {
            val rows = listOf(field("Sender Organization", "N", "sender"), field("Subject", "S", "subject"))
            val p = present(rows)
            assertThat(p.review.mode).isEqualTo(ConfirmMode.ALL)
            assertThat(p.review.open).isEqualTo(2)
            assertThat(p.review.openIds).containsExactlyElementsIn(rows.map { it.id })
        }

        @Test
        fun `with nothing open there is no button, and ignored rows are not open`() {
            val p = present(listOf(field("Subject", "S", "subject", confirmed = true), field("Sender Organization", "x", "sender", deleted = true)))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.NONE)
        }

        @Test
        fun `only uncertain essential lines open means no confident ones to confirm`() {
            val p = present(listOf(field("Subject", "S", "subject", confidence = 0.4f)))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.NONE)
        }

        @Test
        fun `a document with only details has no review button`() {
            val p = present(listOf(field("Amount", "1 €", "total"), field("IBAN", "DE89", "iban")))
            assertThat(p.review.mode).isEqualTo(ConfirmMode.NONE)
        }
    }

    @Nested
    @DisplayName("Ignored footer")
    inner class IgnoredFooter {

        @Test
        fun `ignored rows are collected apart and never drawn in the sections`() {
            val p = present(
                listOf(field("Amount", "1 €", "total"), field("IBAN", "DE89", "iban", deleted = true), extra("Gone").copy(deletedByUser = true)),
                type = "invoice_bill",
            )

            assertThat(p.ignored.map { it.fieldName }).containsExactly("IBAN", "Gone")
            assertThat(p.slotsIn(SectionKind.ACTION)).containsExactly("total")
            assertThat(p.extraCount).isEqualTo(0)
            assertThat(p.essentials.keyInfo).isEmpty()
        }

        @Test
        fun `an ignored row is not uncertain and not counted as open`() {
            val p = present(listOf(field("Subject", "S", "subject", confidence = 0.2f, deleted = true)))
            assertThat(p.checkCount).isEqualTo(0)
            assertThat(p.review.uncertain).isEqualTo(0)
            assertThat(p.ignored).hasSize(1)
            assertThat(p.essentials.subject).isNull()
        }
    }

    @Nested
    @DisplayName("All details")
    inner class Details {

        @Test
        fun `for a document read before the key information the extras are details and keep the AI's label`() {
            val p = present(listOf(field("Amount", "1 €", "total"), extra("Zaehlernummer"), extra("Tarif")), version = "extraction-v2-2")

            assertThat(p.sections.flatMap { it.items }.flatMap { it.rows }.map { it.fieldName }).containsExactly("Amount")
            assertThat(p.extras.map { it.fieldName }).containsExactly("Zaehlernummer", "Tarif").inOrder()
            assertThat(p.hiddenExtras).isEqualTo(0)
            assertThat(p.extraCount).isEqualTo(2)
            assertThat(p.detailCount).isEqualTo(3)
        }

        @Test
        fun `very unsure extras are hidden behind Show all and counted`() {
            val fields = listOf(extra("Sure", 0.9f), extra("Unsure", 0.4f), extra("Fuzzy quote", 0.45f))

            val collapsed = present(fields, version = "extraction-v2-2")
            assertThat(collapsed.extras.map { it.fieldName }).containsExactly("Sure")
            assertThat(collapsed.hiddenExtras).isEqualTo(2)
            assertThat(collapsed.extraCount).isEqualTo(3)
            assertThat(collapsed.detailCount).isEqualTo(3)

            val all = present(fields, showAll = true, version = "extraction-v2-2")
            assertThat(all.hiddenExtras).isEqualTo(0)
            assertThat(all.extras.map { it.fieldName }).containsExactly("Sure", "Unsure", "Fuzzy quote")
        }

        @Test
        fun `an extra the user owns or confirmed is never hidden`() {
            val p = present(listOf(extra("Mine", 0.2f, source = ValueSource.USER), extra("Confirmed", 0.2f, confirmed = true)), version = "extraction-v2-2")
            assertThat(p.extras.map { it.fieldName }).containsExactly("Mine", "Confirmed")
            assertThat(p.hiddenExtras).isEqualTo(0)
        }

        @Test
        fun `the count of All details is every row it holds, an address block counting its parts`() {
            val p = present(
                listOf(
                    field("Receiver Name", "E", "addressee"), part("addressee", "street", "Weg 1"), part("addressee", "city", "Berlin"),
                    field("Amount", "1 €", "total"),
                ),
                type = "invoice_bill",
            )
            assertThat(p.detailCount).isEqualTo(3)
        }
    }
}
