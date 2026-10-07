package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ActionGroundingTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)

    private val letter = """
        Stadtwerke Musterstadt GmbH
        Kundenservice: service@stadtwerke-muster.de
        Musterstadt, 01.10.2026
        Aktenzeichen: AZ-4711/2026
        Rechnungsnummer 2026-0815
        Bitte überweisen Sie 123,45 EUR bis zum 05.11.2026.
    """.trimIndent()

    private val sources = GroundingSources(
        letterText = letter,
        verifiedValues = listOf("Stadtwerke Musterstadt GmbH", "2026-11-05", "123,45 EUR"),
        profileValues = listOf("me@example.org"),
        userMessages = listOf("Please write to them, I will answer on 12 November 2026."),
    )

    private fun check(action: AgentAction, s: GroundingSources = sources) = ActionGrounding.check(action, s, now)

    private fun email(to: String = "service@stadtwerke-muster.de", subject: String = "Re: AZ-4711/2026", body: String = "Guten Tag") =
        AgentAction.SendEmail(to, subject, body)

    // ── e-mail address ──

    @Test
    fun `an address printed in the letter is grounded`() {
        assertThat(check(email())[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `an address in the user's profile is grounded`() {
        assertThat(check(email(to = "me@example.org"))[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `an address the user wrote in the conversation is grounded`() {
        val s = sources.copy(userMessages = listOf("send it to boss@firma.de please"))

        assertThat(check(email(to = "boss@firma.de"), s)[ActionField.TO]).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `an address that is nowhere is flagged, not changed`() {
        val checks = check(email(to = "invented@nowhere.de"))

        assertThat(checks[ActionField.TO]).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `an address one letter off the printed one is not found`() {
        assertThat(check(email(to = "service@stadtwerke-muster.com"))[ActionField.TO]!!.status).isEqualTo(FieldStatus.NOT_FOUND)
        assertThat(check(email(to = "ervice@stadtwerke-muster.de"))[ActionField.TO]!!.status).isEqualTo(FieldStatus.NOT_FOUND)
    }

    @Test
    fun `case of the address does not matter`() {
        assertThat(check(email(to = "Service@Stadtwerke-Muster.DE"))[ActionField.TO]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    @Test
    fun `a malformed address is invalid`() {
        assertThat(check(email(to = "not an address"))[ActionField.TO]).isEqualTo(FieldCheck.invalid(InvalidReason.NOT_AN_EMAIL))
        assertThat(check(email(to = " "))[ActionField.TO]).isEqualTo(FieldCheck.invalid(InvalidReason.REQUIRED))
    }

    // ── figures and references in text ──

    @Test
    fun `a reference, an amount and a date taken from the letter are grounded`() {
        val checks = check(email(subject = "Re: Aktenzeichen AZ-4711/2026", body = "Ich zahle 123,45 EUR bis zum 05.11.2026. Rechnung 2026-0815."))

        assertThat(checks[ActionField.SUBJECT]!!.status).isEqualTo(FieldStatus.GROUNDED)
        assertThat(checks[ActionField.BODY]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    @Test
    fun `an invented amount and reference are listed on the field`() {
        val checks = check(email(body = "Ich zahle 99,00 EUR. Aktenzeichen AZ-9999/2026."))

        val body = checks[ActionField.BODY]!!
        assertThat(body.status).isEqualTo(FieldStatus.NOT_FOUND)
        assertThat(body.unfound).containsExactly("99,00", "AZ-9999/2026")
    }

    @Test
    fun `part of a longer number is not the number`() {
        // 123 and 45 are the two halves of 123,45: only whole values count.
        val checks = check(email(body = "Betrag 123 und 45"))

        assertThat(checks[ActionField.BODY]!!.unfound).containsExactly("123", "45")
    }

    @Test
    fun `a number from the user's own words counts`() {
        val s = sources.copy(userMessages = listOf("Tell them my customer number is K-778899."))

        assertThat(check(email(body = "Meine Kundennummer ist K-778899."), s)[ActionField.BODY]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    @Test
    fun `text without figures has nothing to check`() {
        assertThat(check(email(subject = "Guten Tag", body = "Vielen Dank für Ihren Brief."))[ActionField.BODY]).isEqualTo(FieldCheck.FREE)
    }

    @Test
    fun `today's date in a letter body is not an invention`() {
        assertThat(check(email(body = "Musterstadt, 07.10.2026"))[ActionField.BODY]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    @Test
    fun `a date in the body that is nowhere is flagged`() {
        assertThat(check(email(body = "Ich melde mich am 24.12.2026"))[ActionField.BODY]!!.unfound).containsExactly("24.12.2026")
    }

    @Test
    fun `Arabic-Indic digits in the letter match Latin digits in the action`() {
        val s = GroundingSources(letterText = "رقم الملف ٤٧١١٠٨١٥ بتاريخ ٠٥.١١.٢٠٢٦")

        val checks = check(email(to = "a@b.de", body = "رقم الملف 47110815 والموعد 05.11.2026"), s)

        assertThat(checks[ActionField.BODY]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    // ── calendar event ──

    private fun event(start: LocalDateTime, end: LocalDateTime? = null, title: String = "Frist Stadtwerke", description: String = "AZ-4711/2026") =
        AgentAction.CreateCalendarEvent(title, start, end, description)

    @Test
    fun `an event on the letter's deadline is grounded, in every written form of the date`() {
        val checks = check(event(LocalDateTime.of(2026, 11, 5, 9, 0), LocalDateTime.of(2026, 11, 5, 10, 0)))

        assertThat(checks[ActionField.START]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(checks[ActionField.END]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(checks[ActionField.DESCRIPTION]!!.status).isEqualTo(FieldStatus.GROUNDED)
    }

    @Test
    fun `an event on a date the user wrote in words is grounded`() {
        assertThat(check(event(LocalDateTime.of(2026, 11, 12, 18, 0)))[ActionField.START]).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `an event on a date that is nowhere is flagged`() {
        assertThat(check(event(LocalDateTime.of(2026, 12, 24, 9, 0)))[ActionField.START]).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `an end before the start is invalid and no end is free`() {
        val start = LocalDateTime.of(2026, 11, 5, 9, 0)

        assertThat(check(event(start, start.minusHours(2)))[ActionField.END]).isEqualTo(FieldCheck.invalid(InvalidReason.END_BEFORE_START))
        assertThat(check(event(start, null))[ActionField.END]).isEqualTo(FieldCheck.FREE)
    }

    @Test
    fun `an event title with an invented reference is flagged`() {
        val checks = check(event(LocalDateTime.of(2026, 11, 5, 9, 0), title = "Frist AZ-0000/1999"))

        assertThat(checks[ActionField.TITLE]!!.unfound).containsExactly("AZ-0000/1999")
    }

    // ── reminder ──

    private fun reminder(at: LocalDateTime, text: String = "Rechnung 123,45 EUR zahlen") = AgentAction.ScheduleReminder(at, text, "doc-1")

    @Test
    fun `a reminder on or before the deadline found in the letter is grounded`() {
        assertThat(check(reminder(LocalDateTime.of(2026, 11, 2, 9, 0)))[ActionField.AT]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(check(reminder(LocalDateTime.of(2026, 11, 5, 8, 0)))[ActionField.AT]).isEqualTo(FieldCheck.GROUNDED)
    }

    @Test
    fun `a reminder long after every date of the letter is flagged`() {
        val s = sources.copy(userMessages = emptyList())

        assertThat(check(reminder(LocalDateTime.of(2027, 3, 1, 9, 0)), s)[ActionField.AT]).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `a reminder in the past is invalid`() {
        assertThat(check(reminder(now.minusMinutes(1)))[ActionField.AT]).isEqualTo(FieldCheck.invalid(InvalidReason.IN_THE_PAST))
        assertThat(check(reminder(now))[ActionField.AT]).isEqualTo(FieldCheck.invalid(InvalidReason.IN_THE_PAST))
    }

    @Test
    fun `a reminder with no letter at all is grounded only today`() {
        val none = GroundingSources()

        assertThat(check(reminder(now.plusHours(2)), none)[ActionField.AT]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(check(reminder(now.plusDays(3)), none)[ActionField.AT]).isEqualTo(FieldCheck.NOT_FOUND)
    }

    @Test
    fun `a reminder's text is checked like any text`() {
        val checks = check(reminder(LocalDateTime.of(2026, 11, 2, 9, 0), text = "Zahle 77,00 EUR"))

        assertThat(checks[ActionField.TEXT]!!.unfound).containsExactly("77,00")
    }

    // ── a reminder's offset against the user's words ──

    private fun offsetReminder(at: LocalDateTime, offset: ReminderOffset) =
        AgentAction.ScheduleReminder(at = at, text = "Anrufen", documentId = null, offset = offset)

    private fun said(text: String) = GroundingSources(userMessages = listOf("earlier 5 things", text))

    @Test
    fun `120 minutes when the user said 2 minutes is flagged`() {
        val action = offsetReminder(now.plusMinutes(120), ReminderOffset(0, 0, 120, atTime = false))

        val check = check(action, said("Remind me in 2 minutes"))[ActionField.AT]!!

        assertThat(check.status).isEqualTo(FieldStatus.NOT_FOUND)
        assertThat(check.unsaid).containsExactly(OffsetAmount(120, OffsetUnit.MINUTES))
    }

    @Test
    fun `2 days when the user said tomorrow at 9 is flagged, and 9 is not an amount of days`() {
        val action = offsetReminder(LocalDateTime.of(2026, 10, 9, 9, 0), ReminderOffset(2, 0, 0, atTime = true))

        val check = check(action, said("Remind me tomorrow at 9"))[ActionField.AT]!!

        assertThat(check.unsaid).containsExactly(OffsetAmount(2, OffsetUnit.DAYS))
    }

    @Test
    fun `one day needs no digit, nothing else does`() {
        val tomorrow = offsetReminder(LocalDateTime.of(2026, 10, 8, 9, 0), ReminderOffset(1, 0, 0, atTime = true))
        val oneHour = offsetReminder(now.plusHours(1), ReminderOffset(0, 1, 0, atTime = false))

        assertThat(check(tomorrow, said("Erinnere mich morgen um 9"))[ActionField.AT]!!.unsaid).isEmpty()
        assertThat(check(oneHour, said("Erinnere mich in einer Stunde"))[ActionField.AT]!!.unsaid)
            .containsExactly(OffsetAmount(1, OffsetUnit.HOURS))
    }

    @Test
    fun `an amount the user wrote is fine, in any script`() {
        val twoMinutes = offsetReminder(now.plusMinutes(2), ReminderOffset(0, 0, 2, atTime = false))
        val threeDays = offsetReminder(LocalDateTime.of(2026, 10, 10, 9, 0), ReminderOffset(3, 0, 0, atTime = true))

        assertThat(check(twoMinutes, said("in 2 Minuten bitte"))[ActionField.AT]).isEqualTo(FieldCheck.GROUNDED)
        assertThat(check(threeDays, said("ذكّرني بعد ٣ أيام الساعة ٩"))[ActionField.AT]!!.unsaid).isEmpty()
    }

    @Test
    fun `only the user's message of this turn counts`() {
        val action = offsetReminder(now.plusMinutes(5), ReminderOffset(0, 0, 5, atTime = false))
        val s = GroundingSources(userMessages = listOf("I have 5 minutes", "Remind me later"))

        assertThat(check(action, s)[ActionField.AT]!!.unsaid).containsExactly(OffsetAmount(5, OffsetUnit.MINUTES))
    }

    @Test
    fun `reading the clock has no values to check`() {
        assertThat(check(AgentAction.GetDateTime)).isEmpty()
        assertThat(AgentAction.GetDateTime.requiresConfirmation).isFalse()
        assertThat(email().requiresConfirmation).isTrue()
    }
}
