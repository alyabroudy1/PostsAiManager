package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.ValueSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Extracted tab's pieces drawn for real (Robolectric, real string resources): a row's overflow menu, the collapsed confirmed row,
 * the address block, the composed title and template summary in words, and the essentials on top with "All details" collapsed below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtractedTabUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val confirmed = mutableListOf<List<String>>()
    private val ignored = mutableListOf<List<String>>()
    private val restored = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private val confirmedConfident = mutableListOf<List<String>>()
    private val confirmedAll = mutableListOf<List<String>>()

    private val actions = FieldActions(
        confirm = { confirmed += it },
        ignore = { ignored += it },
        restore = { restored += it },
        edit = { edited += it.id },
    )

    private fun row(
        id: String = "f1",
        slotKey: String = "total",
        value: String = "64,98 €",
        confidence: Float = 0.9f,
        confirmed: Boolean = false,
        source: ValueSource = ValueSource.MACHINE,
        name: String = "Amount",
    ) = ExtractedData(
        id = id, documentId = "d", fieldName = name, fieldValue = value, fieldType = ExtractedFieldType.OTHER,
        confidence = confidence, slotKey = slotKey, isConfirmed = confirmed, source = source,
    )

    private fun openMenuOf(label: String) = compose.onNodeWithContentDescription("More actions for $label").performClick()

    @Test
    fun `a row has one overflow menu with confirm, edit and ignore, each named after the row, and each does its one thing`() {
        compose.setContent { MaterialTheme { FieldRow(row(), actions) } }

        compose.onNodeWithContentDescription("Confirm Amount").assertDoesNotExist()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Confirm Amount").performClick()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Edit Amount").performClick()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Ignore Amount").performClick()

        assertThat(confirmed).containsExactly(listOf("f1"))
        assertThat(edited).containsExactly("f1")
        assertThat(ignored).containsExactly(listOf("f1"))
    }

    @Test
    fun `an uncertain row says it is worth checking and keeps the three actions in its menu`() {
        compose.setContent { MaterialTheme { FieldRow(row(confidence = 0.4f), actions) } }

        compose.onNodeWithText("Worth checking.").assertIsDisplayed()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Confirm Amount").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Amount").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ignore Amount").assertIsDisplayed()
    }

    @Test
    fun `a confirmed row collapses to one line, shows a mark and has no confirm entry, edit and ignore stay`() {
        compose.setContent { MaterialTheme { FieldRow(row(confirmed = true), actions) } }

        compose.onNodeWithText("Amount: 64,98 €").assertIsDisplayed()
        compose.onNodeWithContentDescription("Amount, confirmed").assertIsDisplayed()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Confirm Amount").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit Amount").performClick()
        openMenuOf("Amount")
        compose.onNodeWithContentDescription("Ignore Amount").performClick()

        assertThat(edited).containsExactly("f1")
        assertThat(ignored).containsExactly(listOf("f1"))
    }

    private fun part(id: String, key: String, value: String, confidence: Float = 0.9f, confirmed: Boolean = false) = ExtractedData(
        id = id, documentId = "d", fieldName = "addressee $key", fieldValue = value, fieldType = ExtractedFieldType.ADDRESS,
        confidence = confidence, slotKey = "addressee.$key", isConfirmed = confirmed,
    )

    private fun block(vararg parts: ExtractedData): AddressBlock {
        val name = row(id = "n", slotKey = "addressee", value = "Erika Mustermann").copy(fieldName = "Receiver Name")
        val all = listOf(name) + parts
        val lines = parts.map { AddressLine(listOf(it)) }
        return AddressBlock(PartyRole.ADDRESSEE, name, lines, rawRow = null, rows = all)
    }

    @Test
    fun `an address block confirms and ignores as a whole, and a part is edited on its own`() {
        val b = block(part("s", "street", "Hauptstr. 12"), part("c", "city", "Berlin"))
        compose.setContent { MaterialTheme { AddressBlockCard(b, actions) } }

        compose.onNodeWithText("Erika Mustermann").assertIsDisplayed()
        compose.onNodeWithText("Berlin").performClick()
        compose.onNodeWithContentDescription("Confirm Recipient").performClick()
        compose.onNodeWithContentDescription("Ignore Recipient").performClick()

        assertThat(edited).containsExactly("c")
        assertThat(confirmed.single()).containsExactly("n", "s", "c")
        assertThat(ignored.single()).containsExactly("n", "s", "c")
    }

    @Test
    fun `an uncertain part of a block is marked in its description`() {
        val b = block(part("s", "street", "Hauptstr. 12", confidence = 0.4f))
        compose.setContent { MaterialTheme { AddressBlockCard(b, actions) } }

        compose.onNodeWithContentDescription("Receiver street: Hauptstr. 12, worth checking").assertIsDisplayed()
    }

    @Test
    fun `a confirmed block is one line that opens on a tap`() {
        // Built from confirmed rows (a copy would keep the review state the row was created with).
        val name = row(id = "n", slotKey = "addressee", value = "Erika Mustermann", confirmed = true).copy(fieldName = "Receiver Name")
        val street = part("s", "street", "Hauptstr. 12", confirmed = true)
        val b = AddressBlock(PartyRole.ADDRESSEE, name, listOf(AddressLine(listOf(street))), rawRow = null, rows = listOf(name, street))
        compose.setContent { MaterialTheme { AddressBlockCard(b, actions) } }

        compose.onNodeWithText("Recipient: Erika Mustermann, Hauptstr. 12").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Receiver street: Hauptstr. 12").assertIsDisplayed()
    }

    private val args = listOf("invoice_bill", "Nordlicht Mobilfunk", "Erika", "64,98 €", "15.10.2026", "Zahlungserinnerung")

    @Test
    fun `the summary card shows the composed title, the template summary and the badge from the fields`() {
        val card = SummaryCard(
            titleArgs = listOf("invoice_bill", "Nordlicht Mobilfunk", "Zahlungserinnerung"),
            templateArgs = args,
            summarySource = SummarySource.TEMPLATE,
        )
        compose.setContent { MaterialTheme { SummaryCardView(card, onEditSummary = {}) } }

        compose.onNodeWithText("Invoice or bill · Nordlicht Mobilfunk · Zahlungserinnerung").assertIsDisplayed()
        compose.onNodeWithText(
            "Invoice or bill from Nordlicht Mobilfunk for Erika. Amount 64,98 €, due 15.10.2026. About: Zahlungserinnerung.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Summary from the fields").assertIsDisplayed()
        compose.onNodeWithText("AI summary").assertDoesNotExist()
    }

    @Test
    fun `a template with missing parts drops them without a gap`() {
        val card = SummaryCard(templateArgs = listOf("receipt", "Bäckerei Korn", "", "", "", ""), summarySource = SummarySource.TEMPLATE)
        compose.setContent { MaterialTheme { SummaryCardView(card, onEditSummary = {}) } }

        compose.onNodeWithText("Receipt from Bäckerei Korn.").assertIsDisplayed()
    }

    @Test
    fun `a model summary is badged as AI's, a person's own as theirs, and the pencil edits it`() {
        var edits = 0
        compose.setContent {
            MaterialTheme { SummaryCardView(SummaryCard(summaryText = "Pay by Friday.", summarySource = SummarySource.MODEL), onEditSummary = { edits++ }) }
        }

        compose.onNodeWithText("AI summary").assertIsDisplayed()
        compose.onNodeWithText("Pay by Friday.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit summary").performClick()
        assertThat(edits).isEqualTo(1)
    }

    @Test
    fun `the person's own summary says so`() {
        compose.setContent {
            MaterialTheme { SummaryCardView(SummaryCard(summaryText = "Mine.", summarySource = SummarySource.USER), onEditSummary = {}) }
        }
        compose.onNodeWithText("Your summary").assertIsDisplayed()
    }

    private fun document(extractionType: String? = null, actionItems: List<String> = emptyList(), version: String? = null) = Document(
        id = "d", title = "T", sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        extractionType = extractionType, actionItems = actionItems, extractorVersion = version,
    )

    private fun showTab(
        document: Document,
        data: List<ExtractedData>,
        actions: FieldActions = this.actions,
        onFillForm: (() -> Unit)? = null,
        selfName: String? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                ExtractedTab(
                    document = document, data = data, summaryComing = false, actions = actions,
                    onAddClick = {}, onReprocess = {}, onChangeFamily = {}, onReadAgainAs = {},
                    onConfirmConfident = { confirmedConfident += it }, onConfirmAll = { confirmedAll += it },
                    onUpdateField = { _, _, _ -> }, onUpdateSummary = {}, onShowOnPage = { _, _ -> },
                    onFillForm = onFillForm, selfName = selfName,
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `confirming a row far down the list keeps the scroll position`() {
        fun extra(n: Int, confirmed: Boolean) = ExtractedData(
            id = "e$n", documentId = "d", fieldName = "Extra $n", fieldValue = "v$n", fieldType = ExtractedFieldType.OTHER,
            confidence = 0.9f, slotKey = "x:extra_$n", isConfirmed = confirmed,
        )
        var data by mutableStateOf((1..40).map { extra(it, false) })
        showTab(
            document(),
            data,
            actions = FieldActions(
                confirm = { ids -> data = data.map { if (it.id in ids) it.copy(isConfirmed = true) else it } },
                ignore = {}, restore = {}, edit = {},
            ),
        )
        // A document read before the key information: every extra is a detail, behind the collapsed "All details".
        compose.onNodeWithText("All details (40)").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Extra 30"))
        compose.onNodeWithContentDescription("More actions for Extra 30").performClick()
        compose.onNodeWithContentDescription("Confirm Extra 30").performClick()

        compose.waitForIdle()
        compose.onNodeWithText("Extra 30").assertIsDisplayed()
    }

    @Test
    fun `a form offers the beta card Form filling, which starts the fill`() {
        var started = 0
        showTab(document(ExtractionSchema.FORM_APPLICATION.id), listOf(row()), onFillForm = { started++ })

        compose.onNodeWithText("This is a form to fill in.").assertIsDisplayed()
        compose.onNodeWithText("Form filling (beta)").performClick()

        assertThat(started).isEqualTo(1)
    }

    @Test
    fun `any other document gets no card, the overflow menu item is its entry`() {
        showTab(document("invoice_bill"), listOf(row()), onFillForm = {})

        compose.onNodeWithText("This is a form to fill in.").assertDoesNotExist()
    }

    @Test
    fun `a form without the callback shows no card`() {
        showTab(document(ExtractionSchema.FORM_APPLICATION.id), listOf(row()), onFillForm = null)

        compose.onNodeWithText("Form filling (beta)").assertDoesNotExist()
    }

    @Test
    fun `while the summary is coming the card says so and offers nothing to edit`() {
        compose.setContent {
            MaterialTheme { SummaryCardView(SummaryCard(summaryComing = true), onEditSummary = {}) }
        }

        compose.onNodeWithText("Summary coming…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit summary").assertDoesNotExist()
    }

    // ── the essentials on top, All details below ──

    private val invoiceRows = {
        listOf(
            row("s", "sender", "Nordlicht Mobilfunk GmbH", name = "Sender Organization"),
            row("a", "addressee", "Erika Mustermann", name = "Receiver Name"),
            row("j", "subject", "Zahlungserinnerung", name = "Subject"),
            row("t", "total", "64,98 €", name = "Amount"),
            row("d", "due_date", "15.10.2026", name = "Deadline"),
            row("i", "iban", "DE89 3704 0044 0532 0130 00", name = "IBAN"),
            row("n", "invoice_no", "R-2026-1", name = "Invoice Number"),
            row("m", "x:mandatsreferenz", "M-77", name = "Mandatsreferenz"),
        )
    }
    private val invoice = { document("invoice_bill", listOf("Zahle 64,98 € bis zum 15.10.2026."), ExtractorVersion.CURRENT) }

    @Test
    fun `All details is collapsed by default, shows its count and opens on a tap`() {
        showTab(invoice(), invoiceRows())

        scrollTo("All details (2)")
        compose.onNodeWithText("All details (2)").assertIsDisplayed()
        // The rest of the fields are not drawn while it is collapsed.
        compose.onNodeWithText("Invoice number").assertDoesNotExist()
        compose.onNodeWithText("IBAN").assertDoesNotExist()

        compose.onNodeWithText("All details (2)").performClick()
        scrollTo("Invoice number")
        compose.onNodeWithText("Invoice number").assertIsDisplayed()
        scrollTo("R-2026-1")
        compose.onNodeWithText("R-2026-1").assertIsDisplayed()
    }

    @Test
    fun `the essentials are on top while All details is collapsed`() {
        showTab(invoice(), invoiceRows())

        scrollTo("Nordlicht Mobilfunk GmbH")
        compose.onNodeWithText("Nordlicht Mobilfunk GmbH").assertIsDisplayed()
        scrollTo("Zahlungserinnerung")
        compose.onNodeWithText("Zahlungserinnerung").assertIsDisplayed()
    }

    @Test
    fun `All details reads as a heading with its state, collapsed and then expanded`() {
        showTab(invoice(), invoiceRows())

        scrollTo("All details (2)")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("All details (2)"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText("All details (2)").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("All details (2)"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun `the action line shows the AI's text and the values it states, and confirming it confirms the fields behind it`() {
        showTab(invoice(), invoiceRows())

        scrollTo("What you need to do")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("What you need to do")).assertIsDisplayed()
        scrollTo("Zahle 64,98 € bis zum 15.10.2026.")
        compose.onNodeWithText("Zahle 64,98 € bis zum 15.10.2026.").assertIsDisplayed()
        scrollTo("Deadline: 15.10.2026")
        compose.onNodeWithText("Amount: 64,98 €").assertIsDisplayed()
        compose.onNodeWithText("Deadline: 15.10.2026").assertIsDisplayed()

        compose.onNodeWithContentDescription("Confirm Zahle 64,98 € bis zum 15.10.2026.").performClick()
        assertThat(confirmed.single()).containsExactly("t", "d")
    }

    @Test
    fun `the action line's fields are ignored one by one, or edited in the sheet, from its overflow menu`() {
        showTab(invoice(), invoiceRows())

        scrollTo("Zahle 64,98 € bis zum 15.10.2026.")
        openMenuOf("Zahle 64,98 € bis zum 15.10.2026.")
        compose.onNodeWithContentDescription("Ignore Amount").performClick()
        assertThat(ignored).containsExactly(listOf("t"))

        // The tab itself opens the Edit sheet for the field it is asked to edit.
        openMenuOf("Zahle 64,98 € bis zum 15.10.2026.")
        compose.onNodeWithContentDescription("Edit Deadline").performClick()
        compose.onNodeWithText("Edit Deadline").assertIsDisplayed()
    }

    @Test
    fun `a document with no action lines has no What you need to do section`() {
        showTab(document("invoice_bill", emptyList(), ExtractorVersion.CURRENT), invoiceRows())

        compose.onNodeWithText("What you need to do").assertDoesNotExist()
    }

    @Test
    fun `From and For are shown, For reads You for the Me profile and About only for someone else`() {
        showTab(invoice(), invoiceRows() + row("p", "subject_person", "Mia Mustermann", name = "About Person"), selfName = "erika mustermann")

        scrollTo("Mia Mustermann")
        compose.onNodeWithText("From").assertIsDisplayed()
        compose.onNodeWithText("Nordlicht Mobilfunk GmbH").assertIsDisplayed()
        compose.onNodeWithText("For").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Erika Mustermann").assertDoesNotExist()
        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("Mia Mustermann").assertIsDisplayed()
    }

    @Test
    fun `the key information lists the subject and the AI's picks, a pick with a copy button`() {
        showTab(invoice(), invoiceRows())

        scrollTo("Key information")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Key information")).assertIsDisplayed()
        scrollTo("M-77")
        compose.onNodeWithText("Zahlungserinnerung").assertIsDisplayed()
        compose.onNodeWithText("M-77").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy Mandatsreferenz").assertIsDisplayed()
    }

    @Test
    fun `the overflow menu of a detail row has confirm, edit and ignore, after All details is opened`() {
        showTab(invoice(), invoiceRows())

        scrollTo("All details (2)")
        compose.onNodeWithText("All details (2)").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("R-2026-1"))
        openMenuOf("Invoice number")
        compose.onNodeWithContentDescription("Confirm Invoice number").performClick()
        openMenuOf("Invoice number")
        compose.onNodeWithContentDescription("Ignore Invoice number").performClick()
        assertThat(confirmed).containsExactly(listOf("n"))
        assertThat(ignored).containsExactly(listOf("n"))

        // Edit opens the sheet (the tab binds it itself).
        openMenuOf("Invoice number")
        compose.onNodeWithContentDescription("Edit Invoice number").performClick()
        compose.onNodeWithText("Edit Invoice number").assertIsDisplayed()
    }

    @Test
    fun `an uncertain essential line is counted under Check these and marked in place, an uncertain detail is not counted`() {
        val rows = invoiceRows().map {
            when (it.id) {
                "s" -> it.copy(confidence = 0.3f)
                "n" -> it.copy(confidence = 0.3f)
                else -> it
            }
        }
        showTab(invoice(), rows)

        compose.onNodeWithText("Check these (1)").assertIsDisplayed()
        scrollTo("Worth checking.")
        compose.onNodeWithText("Worth checking.").assertIsDisplayed()
    }

    @Test
    fun `Confirm confident acts on the essential lines only`() {
        val rows = invoiceRows().map { if (it.id == "s") it.copy(confidence = 0.3f) else it }
        showTab(invoice(), rows)

        // The uncertain sender stays; the confident essentials (For, the subject, the amount, the deadline, the key information) are
        // confirmed by id. The invoice number and the IBAN, which are details, are not among them.
        compose.onNodeWithText("Confirm 5 confident").performClick()

        assertThat(confirmedConfident.single()).containsExactly("a", "j", "t", "d", "m")
        assertThat(confirmedConfident.single()).containsNoneOf("n", "i")
    }

    @Test
    fun `an old document shows From and For and the subject, everything else is under All details`() {
        showTab(document("invoice_bill", emptyList(), "extraction-v2-2"), invoiceRows())

        compose.onNodeWithText("What you need to do").assertDoesNotExist()
        scrollTo("Nordlicht Mobilfunk GmbH")
        compose.onNodeWithText("Nordlicht Mobilfunk GmbH").assertIsDisplayed()
        scrollTo("Key information")
        compose.onNodeWithText("Key information").assertIsDisplayed()
        scrollTo("All details (5)")
        compose.onNodeWithText("All details (5)").assertIsDisplayed()
    }
}
