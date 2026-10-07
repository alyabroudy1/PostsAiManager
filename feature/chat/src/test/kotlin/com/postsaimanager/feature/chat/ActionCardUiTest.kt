package com.postsaimanager.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.InvalidReason
import com.postsaimanager.core.domain.skills.ProposedAction
import com.postsaimanager.core.domain.skills.ReminderOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** The action card drawn for real (Robolectric, real string resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
class ActionCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val email = AgentAction.SendEmail("info@amt.de", "Re: AZ-1/2026", "Guten Tag,\n\nich zahle 99,00 EUR.")
    private val flagged = mapOf(
        ActionField.TO to FieldCheck.GROUNDED,
        ActionField.SUBJECT to FieldCheck.GROUNDED,
        ActionField.BODY to FieldCheck(FieldStatus.NOT_FOUND, unfound = listOf("99,00")),
    )

    private var opened = 0
    private var edited = 0
    private var cancelled = 0
    private val changes = mutableListOf<Pair<ActionField, String>>()

    private fun card(
        action: AgentAction = email,
        checks: Map<ActionField, FieldCheck> = flagged,
        status: ActionCardStatus = ActionCardStatus.PENDING,
        editing: Boolean = false,
        errors: Map<ActionField, InvalidReason> = emptyMap(),
        edited: Set<ActionField> = emptySet(),
        openFailed: Boolean = false,
    ) = ActionCardState(
        id = "c1",
        proposed = ProposedAction(action, null, checks),
        values = ActionForm.entries(action).toMap(),
        edited = edited,
        editing = editing,
        errors = errors,
        status = status,
        openFailed = openFailed,
    )

    private fun show(state: ActionCardState) {
        compose.setContent {
            MaterialTheme {
                ActionCard(
                    state = state,
                    onOpen = { opened++ },
                    onEdit = { edited++ },
                    onCancel = { cancelled++ },
                    onChange = { field, text -> changes += field to text },
                )
            }
        }
    }

    @Test
    fun `an email card shows To, Subject and a preview of the body, with Open, Edit and Cancel`() {
        show(card())

        compose.onNodeWithText("Email draft").assertIsDisplayed()
        compose.onNodeWithText("info@amt.de").assertIsDisplayed()
        compose.onNodeWithText("Re: AZ-1/2026").assertIsDisplayed()
        compose.onNodeWithText("Guten Tag,\n\nich zahle 99,00 EUR.").assertIsDisplayed()
        compose.onNodeWithTag("actionOpen").assertIsDisplayed()
        compose.onNodeWithTag("actionEdit").assertIsDisplayed()
        compose.onNodeWithTag("actionCancel").assertIsDisplayed()
    }

    @Test
    fun `a value not found in the letter is flagged on its field, with the values listed`() {
        show(card())

        compose.onNodeWithText("Not found in the letter: 99,00 — check").assertIsDisplayed()
    }

    @Test
    fun `a value that is not found at all is flagged`() {
        show(card(checks = mapOf(ActionField.TO to FieldCheck.NOT_FOUND)))

        compose.onNodeWithText("Not found in the letter — check").assertIsDisplayed()
    }

    @Test
    fun `Open, Edit and Cancel call back and nothing else`() {
        show(card())

        compose.onNodeWithTag("actionEdit").performClick()
        compose.onNodeWithTag("actionCancel").performClick()
        assertThat(opened).isEqualTo(0)
        assertThat(edited).isEqualTo(1)
        assertThat(cancelled).isEqualTo(1)

        compose.onNodeWithTag("actionOpen").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `editing shows a text field per field and reports each change`() {
        show(card(editing = true))

        compose.onNodeWithTag("actionField_TO").performTextReplacement("me@x.de")

        assertThat(changes.last()).isEqualTo(ActionField.TO to "me@x.de")
        compose.onNodeWithTag("actionField_SUBJECT").assertIsDisplayed()
        compose.onNodeWithTag("actionField_BODY").assertIsDisplayed()
        compose.onNodeWithTag("actionEdit").assertDoesNotExist()
        compose.onNodeWithTag("actionOpen").assertIsDisplayed()
    }

    @Test
    fun `an error is shown at its field while editing`() {
        show(card(editing = true, errors = mapOf(ActionField.TO to InvalidReason.NOT_AN_EMAIL)))

        compose.onNodeWithText("Not a valid email address").assertIsDisplayed()
    }

    @Test
    fun `a field the user changed says so instead of the flag`() {
        show(card(checks = mapOf(ActionField.TO to FieldCheck.NOT_FOUND), edited = setOf(ActionField.TO)))

        compose.onNodeWithText("Edited by you").assertIsDisplayed()
        compose.onNodeWithText("Not found in the letter — check").assertDoesNotExist()
    }

    @Test
    fun `an opened card shows its final state and no buttons`() {
        show(card(status = ActionCardStatus.OPENED))

        compose.onNodeWithText("Opened in your mail app").assertIsDisplayed()
        compose.onNodeWithTag("actionOpen").assertDoesNotExist()
        compose.onNodeWithTag("actionCancel").assertDoesNotExist()
        compose.onNodeWithText("info@amt.de").assertIsDisplayed()
    }

    @Test
    fun `a cancelled card shows its final state and no buttons`() {
        show(card(status = ActionCardStatus.CANCELLED))

        compose.onNodeWithText("Cancelled").assertIsDisplayed()
        compose.onNodeWithTag("actionOpen").assertDoesNotExist()
    }

    @Test
    fun `a card whose Open did not work says so and keeps its buttons`() {
        show(card(openFailed = true))

        compose.onNodeWithText("That did not work. Check the fields, or try again.").assertIsDisplayed()
        compose.onNodeWithTag("actionOpen").assertIsDisplayed()
    }

    @Test
    fun `a calendar card shows the title, the start and the notes, and an unset end is not shown`() {
        val event = AgentAction.CreateCalendarEvent("Frist Stadtwerke", LocalDateTime.of(2026, 11, 5, 9, 0), null, "AZ-1/2026")
        show(card(action = event, checks = emptyMap()))

        compose.onNodeWithText("Calendar event").assertIsDisplayed()
        compose.onNodeWithText("Frist Stadtwerke").assertIsDisplayed()
        compose.onNodeWithText("2026-11-05 09:00").assertIsDisplayed()
        compose.onNodeWithTag("actionRow_END").assertDoesNotExist()
        compose.onNodeWithTag("actionRow_DESCRIPTION").assertIsDisplayed()
    }

    @Test
    fun `a reminder card shows the time and the text, and its final state says it is set`() {
        val reminder = AgentAction.ScheduleReminder(LocalDateTime.of(2026, 11, 2, 9, 0), "Pay 123,45 EUR", "d1")
        show(card(action = reminder, checks = emptyMap(), status = ActionCardStatus.OPENED))

        compose.onNodeWithText("2026-11-02 09:00").assertIsDisplayed()
        compose.onNodeWithText("Pay 123,45 EUR").assertIsDisplayed()
        compose.onNodeWithText("Reminder set").assertIsDisplayed()
        compose.onNodeWithTag("actionUnderstood").assertDoesNotExist()
    }

    @Test
    fun `a reminder made from an offset shows what was understood next to the time`() {
        val at = LocalDateTime.of(2026, 10, 7, 18, 1)
        show(card(action = AgentAction.ScheduleReminder(at, "Pay", "d1", ReminderOffset(0, 2, 0, atTime = false)), checks = emptyMap()))

        compose.onNodeWithTag("actionUnderstood").assertTextEquals("In 2 hours · 18:01")
    }

    @Test
    fun `a reminder for a day at a time of day says today or tomorrow`() {
        val tomorrow = AgentAction.ScheduleReminder(LocalDateTime.of(2026, 10, 8, 9, 0), "Pay", "d1", ReminderOffset(1, 0, 0, atTime = true))
        show(card(action = tomorrow, checks = emptyMap()))

        compose.onNodeWithTag("actionUnderstood").assertTextEquals("Tomorrow · 09:00")
    }
}
