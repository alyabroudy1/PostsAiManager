package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.domain.extraction.actions.ActionLines
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The letter page's contact chip ("From: Jobcenter Musterstadt · Frau Nadine Beispiel") and the contact action's offer, drawn for real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterContactUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = FieldActions(confirm = {}, ignore = {}, restore = {}, edit = {})

    private val sender = ExtractedData(
        id = "s", documentId = "d1", fieldName = "Sender Organization", fieldValue = "Jobcenter Musterstadt",
        fieldType = ExtractedFieldType.ORGANIZATION, confidence = 0.9f, slotKey = "sender",
    )

    private val nadine = ContactPerson("nadine", "jc", "Frau Nadine Beispiel", phone = "030 111", firstSeen = 1, lastSeen = 10)
    private val mueller = ContactPerson("mueller", "jc", "Frau Müller", phone = "030 222", email = "mueller@jc.example", firstSeen = 20, lastSeen = 30)

    @Test
    fun `the sender row carries the letter's contact as a chip that opens the organisation at that contact`() {
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                PartiesCard(
                    PartiesView(from = PartyEntry(sender), forWhom = null, about = null),
                    actions,
                    LetterContacts(letterContact = nadine, current = mueller, organisationId = "jc", organisationName = "Jobcenter Musterstadt"),
                ) { organisationId, contactId -> opened += organisationId to contactId }
            }
        }

        compose.onNodeWithText("Jobcenter Musterstadt").assertIsDisplayed()
        compose.onNodeWithText("Frau Nadine Beispiel").assertIsDisplayed()
        compose.onNodeWithTag("letter_contact_chip").performClick()

        // The chip is the letter's contact (Nadine), not the organisation's current one (Müller).
        assertThat(opened).containsExactly("jc" to "nadine")
    }

    @Test
    fun `a letter with no linked contact has no chip`() {
        compose.setContent {
            MaterialTheme {
                PartiesCard(PartiesView(from = PartyEntry(sender), forWhom = null, about = null), actions, LetterContacts(current = mueller))
            }
        }

        compose.onNodeWithText("Jobcenter Musterstadt").assertIsDisplayed()
        compose.onNodeWithTag("letter_contact_chip").assertDoesNotExist()
    }

    @Test
    fun `a contact action offers the current contact's phone and e-mail`() {
        val called = mutableListOf<String>()
        val written = mutableListOf<String>()
        val lines = ActionLines.resolve(listOf(ActionItem("contact", mapOf("party" to "sender"))), listOf(sender), mueller)
        compose.setContent { MaterialTheme { ActionsCard(lines, actions, onCall = { called += it }, onEmail = { written += it }) } }

        compose.onNodeWithTag("action_offer_call").performClick()
        compose.onNodeWithTag("action_offer_email").performClick()

        assertThat(called).containsExactly("030 222")
        assertThat(written).containsExactly("mueller@jc.example")
        compose.onNodeWithText("Call Frau Müller").assertIsDisplayed()
    }

    @Test
    fun `no offer is drawn without a contact`() {
        val lines = ActionLines.resolve(listOf(ActionItem("contact", mapOf("party" to "sender"))), listOf(sender))
        compose.setContent { MaterialTheme { ActionsCard(lines, actions) } }

        compose.onNodeWithTag("action_offer_call").assertDoesNotExist()
        compose.onNodeWithTag("action_offer_email").assertDoesNotExist()
    }
}
