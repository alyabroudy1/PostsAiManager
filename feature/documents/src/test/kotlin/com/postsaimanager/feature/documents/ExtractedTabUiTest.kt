package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.ValueSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Extracted tab's pieces drawn for real (Robolectric, real string resources): the three inline actions, the
 * collapsed confirmed row, the address block, and the composed title and template summary in words.
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
    ) = ExtractedData(
        id = id, documentId = "d", fieldName = "Amount", fieldValue = value, fieldType = ExtractedFieldType.OTHER,
        confidence = confidence, slotKey = slotKey, isConfirmed = confirmed, source = source,
    )

    @Test
    fun `a row has confirm, edit and ignore inline, each named after the row, and each does its one thing`() {
        compose.setContent { MaterialTheme { FieldRow(row(), actions) } }

        compose.onNodeWithContentDescription("Confirm Amount").performClick()
        compose.onNodeWithContentDescription("Edit Amount").performClick()
        compose.onNodeWithContentDescription("Ignore Amount").performClick()

        assertThat(confirmed).containsExactly(listOf("f1"))
        assertThat(edited).containsExactly("f1")
        assertThat(ignored).containsExactly(listOf("f1"))
    }

    @Test
    fun `an uncertain row says it is worth checking and has the same three actions`() {
        compose.setContent { MaterialTheme { FieldRow(row(confidence = 0.4f), actions) } }

        compose.onNodeWithText("Worth checking.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Confirm Amount").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Amount").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ignore Amount").assertIsDisplayed()
    }

    @Test
    fun `a confirmed row collapses to one line, shows a mark instead of the confirm button and keeps edit and ignore`() {
        compose.setContent { MaterialTheme { FieldRow(row(confirmed = true), actions) } }

        compose.onNodeWithText("Amount: 64,98 €").assertIsDisplayed()
        compose.onNodeWithContentDescription("Confirm Amount").assertDoesNotExist()
        compose.onNodeWithContentDescription("Amount, confirmed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit Amount").performClick()
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

    @Test
    fun `confirming a row far down the list keeps the scroll position`() {
        fun extra(n: Int, confirmed: Boolean) = ExtractedData(
            id = "e$n", documentId = "d", fieldName = "Extra $n", fieldValue = "v$n", fieldType = ExtractedFieldType.OTHER,
            confidence = 0.9f, slotKey = "x:extra_$n", isConfirmed = confirmed,
        )
        val document = com.postsaimanager.core.model.Document(id = "d", title = "T", sourceType = com.postsaimanager.core.model.SourceType.CAMERA, createdAt = 0, modifiedAt = 0)
        var data by androidx.compose.runtime.mutableStateOf((1..40).map { extra(it, false) })
        compose.setContent {
            MaterialTheme {
                ExtractedTab(
                    document = document, data = data, summaryComing = false, actions = FieldActions(
                        confirm = { ids -> data = data.map { if (it.id in ids) it.copy(isConfirmed = true) else it } },
                        ignore = {}, restore = {}, edit = {},
                    ),
                    onAddClick = {}, onReprocess = {}, onChangeFamily = {}, onReadAgainAs = {}, onConfirmConfident = {}, onConfirmAll = {},
                    onUpdateField = { _, _, _ -> }, onUpdateSummary = {}, onShowOnPage = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Other details (40)").performClick()
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Extra 30"))
        compose.onNodeWithContentDescription("Confirm Extra 30").performClick()

        compose.waitForIdle()
        compose.onNodeWithText("Extra 30").assertIsDisplayed()
    }

    @Test
    fun `while the summary is coming the card says so and offers nothing to edit`() {
        compose.setContent {
            MaterialTheme { SummaryCardView(SummaryCard(typeId = "invoice_bill", summaryComing = true), onEditSummary = {}) }
        }

        compose.onNodeWithText("Summary coming…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit summary").assertDoesNotExist()
    }
}
