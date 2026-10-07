package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ActionFormTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val start = LocalDateTime.of(2026, 11, 5, 9, 0)

    private val email = AgentAction.SendEmail("a@b.de", "Subject", "Line one\nLine two")
    private val event = AgentAction.CreateCalendarEvent("Frist", start, null, "AZ 1")
    private val reminder = AgentAction.ScheduleReminder(start, "Pay", "d1")

    private fun built(template: AgentAction, edits: Map<ActionField, String> = emptyMap()): AgentAction =
        (ActionForm.build(template, edits, now) as FormResult.Built).action

    private fun errors(template: AgentAction, edits: Map<ActionField, String>): Map<ActionField, InvalidReason> =
        (ActionForm.build(template, edits, now) as FormResult.Invalid).errors

    @Test
    fun `the fields of each action in card order`() {
        assertThat(ActionForm.entries(email).map { it.first }).containsExactly(ActionField.TO, ActionField.SUBJECT, ActionField.BODY).inOrder()
        assertThat(ActionForm.entries(event).toMap()[ActionField.START]).isEqualTo("2026-11-05 09:00")
        assertThat(ActionForm.entries(event).toMap()[ActionField.END]).isEmpty()
        assertThat(ActionForm.entries(reminder).map { it.first }).containsExactly(ActionField.AT, ActionField.TEXT).inOrder()
        assertThat(ActionForm.entries(AgentAction.GetDateTime)).isEmpty()
    }

    @Test
    fun `unedited fields rebuild the same action`() {
        assertThat(built(email)).isEqualTo(email)
        assertThat(built(event)).isEqualTo(event)
        assertThat(built(reminder)).isEqualTo(reminder)
    }

    @Test
    fun `an edited field replaces the proposal's value and the rest is kept`() {
        val action = built(email, mapOf(ActionField.TO to " other@x.de ", ActionField.BODY to "New body")) as AgentAction.SendEmail

        assertThat(action.to).isEqualTo("other@x.de")
        assertThat(action.subject).isEqualTo("Subject")
        assertThat(action.body).isEqualTo("New body")
    }

    @Test
    fun `an edited date and time is read back`() {
        val action = built(event, mapOf(ActionField.START to "2026-11-06 18:15", ActionField.END to "2026-11-06 19:00")) as AgentAction.CreateCalendarEvent

        assertThat(action.start).isEqualTo(LocalDateTime.of(2026, 11, 6, 18, 15))
        assertThat(action.end).isEqualTo(LocalDateTime.of(2026, 11, 6, 19, 0))
    }

    @Test
    fun `a bad address, a missing title and a bad time are reported at their fields`() {
        assertThat(errors(email, mapOf(ActionField.TO to "nope"))).containsExactly(ActionField.TO, InvalidReason.NOT_AN_EMAIL)
        assertThat(errors(email, mapOf(ActionField.TO to " "))).containsExactly(ActionField.TO, InvalidReason.REQUIRED)
        assertThat(errors(event, mapOf(ActionField.TITLE to ""))).containsExactly(ActionField.TITLE, InvalidReason.REQUIRED)
        assertThat(errors(event, mapOf(ActionField.START to "31.02.2026"))).containsExactly(ActionField.START, InvalidReason.NOT_A_DATE_TIME)
        assertThat(errors(event, mapOf(ActionField.END to "soon"))).containsExactly(ActionField.END, InvalidReason.NOT_A_DATE_TIME)
    }

    @Test
    fun `an end before the start is reported`() {
        assertThat(errors(event, mapOf(ActionField.END to "2026-11-05 08:00"))).containsExactly(ActionField.END, InvalidReason.END_BEFORE_START)
    }

    @Test
    fun `a reminder in the past is reported, and its text is required`() {
        assertThat(errors(reminder, mapOf(ActionField.AT to "2026-10-07 11:59"))).containsExactly(ActionField.AT, InvalidReason.IN_THE_PAST)
        assertThat(errors(reminder, mapOf(ActionField.TEXT to " "))).containsExactly(ActionField.TEXT, InvalidReason.REQUIRED)
    }

    @Test
    fun `an edited reminder keeps the letter it opens`() {
        val action = built(reminder, mapOf(ActionField.TEXT to "Pay the bill")) as AgentAction.ScheduleReminder

        assertThat(action.documentId).isEqualTo("d1")
    }

    @Test
    fun `what the model said about a reminder's time lasts only until the user changes the time`() {
        val said = ReminderOffset(days = 0, hours = 2, minutes = 0, atTime = false)
        val offsetReminder = AgentAction.ScheduleReminder(start, "Pay", "d1", said)

        assertThat((built(offsetReminder, mapOf(ActionField.TEXT to "Pay it")) as AgentAction.ScheduleReminder).offset).isEqualTo(said)
        assertThat((built(offsetReminder, mapOf(ActionField.AT to "2026-11-06 10:00")) as AgentAction.ScheduleReminder).offset).isNull()
    }

    // ── the intents these actions become ──

    @Test
    fun `the e-mail opens a mail composer with ACTION_SENDTO and mailto, addressed and filled`() {
        val spec = AgentIntentSpecs.of(email)!!

        assertThat(spec.action).isEqualTo("android.intent.action.SENDTO")
        assertThat(spec.data).isEqualTo("mailto:")
        assertThat(spec.extras["android.intent.extra.EMAIL"]).isEqualTo(IntentExtra.TextList(listOf("a@b.de")))
        assertThat(spec.extras["android.intent.extra.SUBJECT"]).isEqualTo(IntentExtra.Text("Subject"))
        assertThat(spec.extras["android.intent.extra.TEXT"]).isEqualTo(IntentExtra.Text("Line one\nLine two"))
    }

    @Test
    fun `the calendar event opens the calendar's insert screen with the times in milliseconds`() {
        val zone = ZoneId.of("Europe/Berlin")
        val spec = AgentIntentSpecs.of(event.copy(end = start.plusHours(1)), zone)!!

        assertThat(spec.action).isEqualTo("android.intent.action.INSERT")
        assertThat(spec.data).isEqualTo("content://com.android.calendar/events")
        assertThat(spec.extras["title"]).isEqualTo(IntentExtra.Text("Frist"))
        assertThat(spec.extras["description"]).isEqualTo(IntentExtra.Text("AZ 1"))
        assertThat(spec.extras["beginTime"]).isEqualTo(IntentExtra.Millis(start.atZone(zone).toInstant().toEpochMilli()))
        assertThat(spec.extras["endTime"]).isEqualTo(IntentExtra.Millis(start.plusHours(1).atZone(zone).toInstant().toEpochMilli()))
    }

    @Test
    fun `an event without an end sends no end time, and a reminder opens no other app`() {
        assertThat(AgentIntentSpecs.of(event)!!.extras).doesNotContainKey("endTime")
        assertThat(AgentIntentSpecs.of(reminder)).isNull()
        assertThat(AgentIntentSpecs.of(AgentAction.GetDateTime)).isNull()
    }
}
