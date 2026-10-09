package com.postsaimanager.feature.documents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.domain.document.actions.ActionEdit
import com.postsaimanager.core.domain.extraction.actions.ActionLines
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.domain.timeline.CaseChoices
import com.postsaimanager.core.domain.timeline.CaseTarget
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ActionSource
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ways to change what the AI filled in on a letter, drawn for real: each hands the person's choice to its callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterEditUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = FieldActions(confirm = {}, ignore = {}, restore = {}, edit = {})

    private val dueDate = ExtractedData(
        id = "due", documentId = "d1", fieldName = "Due date", fieldValue = "15.10.2026", fieldType = ExtractedFieldType.DATE,
        confidence = 0.9f, slotKey = "due_date",
    )
    private val sender = ExtractedData(
        id = "s", documentId = "d1", fieldName = "Sender Organization", fieldValue = "Jobcenter Musterstadt",
        fieldType = ExtractedFieldType.ORGANIZATION, confidence = 0.9f, slotKey = "sender",
    )
    private val reply = ActionItem("reply", mapOf("date" to "due_date"))

    private fun profile(id: String, name: String) =
        Profile(id = id, kind = ProfileType.FAMILY_MEMBER.kind, householdRole = ProfileType.FAMILY_MEMBER.householdRole, name = name, createdAt = 0L, modifiedAt = 0L)

    // ── the actions ──

    @Test
    fun `an action's menu offers Edit action and Delete action, and delete hands over the stored action`() {
        val deleted = mutableListOf<ActionItem>()
        val lines = ActionLines.resolve(listOf(reply), listOf(dueDate))
        compose.setContent { MaterialTheme { ActionsCard(lines, rows, edits = ActionEditActions(delete = { deleted += it })) } }

        compose.onNodeWithContentDescription("More actions for Reply", substring = true).performClick()
        compose.onNodeWithText("Edit action").assertIsDisplayed()
        compose.onNodeWithText("Delete action").performClick()

        assertThat(deleted).containsExactly(reply)
    }

    @Test
    fun `editing an action starts on its sentence, its kind and its date, and saving a changed kind hands over the edit`() {
        val edits = mutableListOf<Pair<ActionItem, ActionEdit>>()
        val lines = ActionLines.resolve(listOf(reply), listOf(dueDate))
        compose.setContent { MaterialTheme { ActionsCard(lines, rows, edits = ActionEditActions(edit = { original, edit -> edits += original to edit })) } }

        compose.onNodeWithContentDescription("More actions for Reply", substring = true).performClick()
        compose.onNodeWithText("Edit action").performClick()
        compose.onNodeWithTag("action_kind_reply").assertIsSelected()
        compose.onNodeWithTag("action_kind_contact").performClick()
        compose.onNodeWithTag("action_save").performClick()

        val (original, edit) = edits.single()
        assertThat(original).isEqualTo(reply)
        assertThat(edit.kind).isEqualTo("contact")
        // Neither the sentence nor the date was changed: nothing is frozen as the person's own.
        assertThat(edit.text).isNull()
        assertThat(edit.dueDate).isNull()
    }

    @Test
    fun `changed wording is handed over as the person's own`() {
        val edits = mutableListOf<ActionEdit>()
        val lines = ActionLines.resolve(listOf(reply), listOf(dueDate))
        compose.setContent { MaterialTheme { ActionsCard(lines, rows, edits = ActionEditActions(edit = { _, edit -> edits += edit })) } }

        compose.onNodeWithContentDescription("More actions for Reply", substring = true).performClick()
        compose.onNodeWithText("Edit action").performClick()
        compose.onNodeWithTag("action_text_field").performTextClearance()
        compose.onNodeWithTag("action_text_field").performTextInput("Answer the letter from the Jobcenter")
        compose.onNodeWithTag("action_save").performClick()

        assertThat(edits.single().text).isEqualTo("Answer the letter from the Jobcenter")
    }

    @Test
    fun `the add button opens an empty form and hands over the kind and wording`() {
        val added = mutableListOf<ActionEdit>()
        val lines = ActionLines.resolve(listOf(reply), listOf(dueDate))
        compose.setContent { MaterialTheme { ActionsCard(lines, rows, edits = ActionEditActions(add = { added += it })) } }

        compose.onNodeWithTag("action_add").performClick()
        // A kind that says nothing needs the person's words before it can be saved.
        compose.onNodeWithTag("action_text_field").performTextInput("Ask the neighbour")
        compose.onNodeWithTag("action_save").performClick()

        assertThat(added.single().kind).isEqualTo("other_action")
        assertThat(added.single().text).isEqualTo("Ask the neighbour")
    }

    @Test
    fun `an action with the person's own wording is shown in their words`() {
        val mine = ActionItem("other_action", source = ActionSource.USER, text = "Ask the neighbour")
        val lines = ActionLines.resolve(listOf(mine), emptyList())
        compose.setContent { MaterialTheme { ActionsCard(lines, rows) } }

        compose.onNodeWithText("Ask the neighbour").assertIsDisplayed()
    }

    // ── who the letter is for ──

    @Test
    fun `a tap on a household person adds them, a tap on a chosen one removes them, and the whole list is handed over`() {
        val changes = mutableListOf<List<String>>()
        compose.setContent {
            MaterialTheme {
                PeopleCard(
                    people = listOf(profile("me", "Erika Mustermann"), profile("maria", "Maria Ahmed")),
                    selectedIds = listOf("maria"),
                    setByUser = false,
                    onChange = { changes += it },
                )
            }
        }

        compose.onNodeWithTag("people_chip_maria").assertIsSelected()
        compose.onNodeWithTag("people_chip_me").performClick()
        compose.onNodeWithTag("people_chip_maria").performClick()

        assertThat(changes).containsExactly(listOf("maria", "me"), emptyList<String>()).inOrder()
    }

    @Test
    fun `the card says when the people are the user's own choice`() {
        compose.setContent { MaterialTheme { PeopleCard(listOf(profile("me", "Erika")), listOf("me"), setByUser = true, onChange = {}) } }

        compose.onNodeWithText("Set by you").assertIsDisplayed()
    }

    // ── the contact person ──

    @Test
    fun `the letter's contact has a pencil that edits its name, role, phone and e-mail`() {
        val edited = mutableListOf<ContactPerson>()
        val nadine = ContactPerson("nadine", "jc", "Frau Nadine Beispiel", title = "Sachbearbeiterin", phone = "030 111", firstSeen = 1, lastSeen = 10)
        compose.setContent {
            MaterialTheme {
                PartiesCard(
                    PartiesView(from = PartyEntry(sender), forWhom = null, about = null),
                    rows,
                    LetterContacts(letterContact = nadine, organisationId = "jc"),
                    LetterContactActions(edit = { edited += it }),
                )
            }
        }

        compose.onNodeWithTag("letter_contact_pencil").performClick()
        compose.onNodeWithTag("contact_phone_field").performTextClearance()
        compose.onNodeWithTag("contact_phone_field").performTextInput("030 999")
        compose.onNodeWithTag("contact_email_field").performTextInput("nadine@jc.example")
        compose.onNodeWithTag("contact_save").performClick()

        val saved = edited.single()
        assertThat(saved.id).isEqualTo("nadine")
        assertThat(saved.name).isEqualTo("Frau Nadine Beispiel")
        assertThat(saved.title).isEqualTo("Sachbearbeiterin")
        assertThat(saved.phone).isEqualTo("030 999")
        assertThat(saved.email).isEqualTo("nadine@jc.example")
    }

    // ── the matter ──

    private val first = Case(id = "c1", organisationProfileId = "jc", title = "Housing benefit", createdAt = 1L)
    private val second = Case(id = "c2", organisationProfileId = "jc", title = "Child benefit", createdAt = 2L)

    @Test
    fun `the move dialog starts on the current matter and hands over another one`() {
        val picked = mutableListOf<CaseTarget>()
        compose.setContent { MaterialTheme { MoveToCaseDialog(CaseChoices("c1", listOf(first, second)), onDismiss = {}, onPick = { picked += it }) } }

        compose.onNodeWithTag("case_choice_c1").assertIsSelected()
        compose.onNodeWithText("Housing benefit (here now)").assertIsDisplayed()
        compose.onNodeWithTag("case_choice_c2").performClick()
        compose.onNodeWithTag("case_move_save").performClick()

        assertThat(picked).containsExactly(CaseTarget.Existing("c2"))
    }

    @Test
    fun `the move dialog can take the letter out of every matter or put it into a new, named one`() {
        val picked = mutableListOf<CaseTarget>()
        compose.setContent { MaterialTheme { MoveToCaseDialog(CaseChoices("c1", listOf(first)), onDismiss = {}, onPick = { picked += it }) } }

        compose.onNodeWithTag("case_choice_none").performClick()
        compose.onNodeWithTag("case_move_save").performClick()
        compose.onNodeWithTag("case_choice_new").performClick()
        compose.onNodeWithTag("case_new_title").performTextInput("Rent arrears")
        compose.onNodeWithTag("case_move_save").performClick()

        assertThat(picked).containsExactly(CaseTarget.None, CaseTarget.New("Rent arrears")).inOrder()
    }

    // ── the meaning of a date ──

    @Test
    fun `the meaning chips offer the meanings of a date, the current one selected, and hand over the choice`() {
        val chosen = mutableListOf<String?>()
        val choices = ValueMeanings.DEFAULT.of(MeaningKind.DATE)
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column { MeaningChips(choices, "APPOINTMENT") { chosen += it } } } }

        compose.onNodeWithTag("meaning_APPOINTMENT").assertIsSelected()
        compose.onNodeWithTag("meaning_DUE_DATE").performClick()
        compose.onNodeWithTag("meaning_none").performClick()

        assertThat(chosen).containsExactly("DUE_DATE", null).inOrder()
    }

    // ── the language ──

    @Test
    fun `the language row names the language and its dialog hands over another one`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    LanguageRow("de", setByUser = false, onEdit = {})
                    LanguageDialog(current = "de", onDismiss = {}, onPick = { picked += it })
                }
            }
        }

        compose.onNodeWithTag("language_row").assertIsDisplayed()
        compose.onNodeWithTag("language_save").assertIsNotEnabled()
        compose.onNodeWithTag("language_ar").performClick()
        compose.onNodeWithTag("language_save").performClick()

        assertThat(picked).containsExactly("ar")
    }

    // ── the title ──

    @Test
    fun `the summary card's title has a pencil that renames the letter`() {
        var renames = 0
        compose.setContent {
            MaterialTheme {
                SummaryCardView(SummaryCard(plainTitle = "Rent letter", summaryText = "A letter."), onEditSummary = {}, onEditTitle = { renames++ })
            }
        }

        compose.onNodeWithText("Rent letter").assertIsDisplayed()
        compose.onNodeWithTag("title_edit").performClick()

        assertThat(renames).isEqualTo(1)
    }

    @Test
    fun `the pages card's title has a pencil that renames the letter`() {
        var renames = 0
        compose.setContent {
            MaterialTheme { PagesSummaryCard("My letter", PagesSummary(PagesSummaryState.READY), onInstall = {}, onEditTitle = { renames++ }) }
        }

        compose.onNodeWithTag("title_edit").performClick()

        assertThat(renames).isEqualTo(1)
    }

    @Test
    fun `without a rename callback the title has no pencil`() {
        compose.setContent { MaterialTheme { PagesSummaryCard("My letter", PagesSummary(PagesSummaryState.READY), onInstall = {}) } }

        compose.onNodeWithTag("title_edit").assertDoesNotExist()
    }
}
