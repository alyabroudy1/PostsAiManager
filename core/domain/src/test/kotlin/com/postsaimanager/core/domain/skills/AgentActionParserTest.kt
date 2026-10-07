package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AgentActionParserTest {

    private fun parsed(intent: String, parameters: String, doc: String? = null): AgentAction =
        (AgentActionParser.parse(intent, parameters, doc) as ActionParse.Parsed).action

    private fun rejected(intent: String, parameters: String): String =
        (AgentActionParser.parse(intent, parameters) as ActionParse.Rejected).reason

    @Test
    fun `send_email reads the Gallery's parameter names`() {
        val action = parsed("send_email", """{"extra_email":"a@b.de","extra_subject":"Hi","extra_text":"Body text"}""")

        assertThat(action).isEqualTo(AgentAction.SendEmail("a@b.de", "Hi", "Body text"))
    }

    @Test
    fun `send_email needs an address, the rest may be empty`() {
        assertThat(rejected("send_email", """{"extra_subject":"Hi"}""")).contains("extra_email")
        assertThat(parsed("send_email", """{"extra_email":"a@b.de"}""")).isEqualTo(AgentAction.SendEmail("a@b.de", "", ""))
    }

    @Test
    fun `create_calendar_event reads the times`() {
        val action = parsed(
            "create_calendar_event",
            """{"title":"Frist","description":"AZ 1","begin_time":"2026-11-05T09:00:00","end_time":"2026-11-05T10:30:00"}""",
        )

        assertThat(action).isEqualTo(
            AgentAction.CreateCalendarEvent("Frist", LocalDateTime.of(2026, 11, 5, 9, 0), LocalDateTime.of(2026, 11, 5, 10, 30), "AZ 1"),
        )
    }

    @Test
    fun `an event without an end time is fine and one without a start is not`() {
        val action = parsed("create_calendar_event", """{"title":"Frist","begin_time":"2026-11-05 09:00"}""") as AgentAction.CreateCalendarEvent

        assertThat(action.end).isNull()
        assertThat(action.description).isEmpty()
        assertThat(rejected("create_calendar_event", """{"title":"Frist"}""")).contains("begin_time")
    }

    @Test
    fun `an impossible date is refused with a hint the model can use`() {
        assertThat(rejected("create_calendar_event", """{"title":"x","begin_time":"2026-02-31T09:00:00"}""")).contains("begin_time")
        assertThat(rejected("create_calendar_event", """{"title":"x","begin_time":"2026-11-05T09:00:00","end_time":"tomorrow"}""")).contains("end_time")
        assertThat(rejected("create_calendar_event", """{"title":"x","begin_time":"2026-11-05T25:00:00"}""")).contains("begin_time")
    }

    @Test
    fun `schedule_notification builds the time from its numbers, as numbers or as digit strings`() {
        val action = parsed(
            "schedule_notification",
            """{"message":"Pay","year":2026,"month":"11","day":2,"hour":9.0,"minute":"30","document_id":"d7"}""",
            doc = "d7",
        )

        assertThat(action).isEqualTo(AgentAction.ScheduleReminder(LocalDateTime.of(2026, 11, 2, 9, 30), "Pay", "d7"))
    }

    private fun reminderDocument(parameters: String, chat: String?, known: Set<String> = emptySet()): String? {
        val parse = AgentActionParser.parse("schedule_notification", parameters, chat, knownDocumentIds = known) as ActionParse.Parsed
        return (parse.action as AgentAction.ScheduleReminder).documentId
    }

    private val invented = """{"message":"Pay","in_minutes":5,"document_id":"BG-12345BG0001234"}"""

    @Test
    fun `an invented document_id is ignored and the reminder points at the chat's document`() {
        assertThat(reminderDocument(invented, chat = "d1")).isEqualTo("d1")
        assertThat(reminderDocument(invented, chat = null)).isNull()
    }

    @Test
    fun `a document_id is used when it is the chat's document or a known one`() {
        assertThat(reminderDocument("""{"message":"Pay","in_minutes":5,"document_id":"d1"}""", chat = "d1")).isEqualTo("d1")
        assertThat(reminderDocument("""{"message":"Pay","in_minutes":5,"document_id":"d2"}""", chat = "d1", known = setOf("d2"))).isEqualTo("d2")
        assertThat(reminderDocument("""{"message":"Pay","in_minutes":5,"document_id":"d2"}""", chat = null, known = setOf("d2"))).isEqualTo("d2")
    }

    @Test
    fun `a reminder without a document_id uses the chat's document`() {
        assertThat(reminderDocument("""{"message":"Pay","in_minutes":5}""", chat = "d1")).isEqualTo("d1")
    }

    private val clock = LocalDateTime.of(2026, 10, 7, 14, 30, 45)

    private fun reminderAt(parameters: String): LocalDateTime =
        ((AgentActionParser.parse("schedule_notification", parameters, now = clock) as ActionParse.Parsed).action as AgentAction.ScheduleReminder).at

    @Test
    fun `a relative offset is added to the clock at proposal time`() {
        assertThat(reminderAt("""{"message":"Pay","in_minutes":2}""")).isEqualTo(LocalDateTime.of(2026, 10, 7, 14, 32))
        assertThat(reminderAt("""{"message":"Pay","in_hours":"3"}""")).isEqualTo(LocalDateTime.of(2026, 10, 7, 17, 30))
        assertThat(reminderAt("""{"message":"Pay","in_days":30}""")).isEqualTo(LocalDateTime.of(2026, 11, 6, 14, 30))
        assertThat(reminderAt("""{"message":"Pay","in_hours":1,"in_minutes":30}""")).isEqualTo(LocalDateTime.of(2026, 10, 7, 16, 0))
    }

    @Test
    fun `an offset wins over an absolute time when both are given`() {
        val params = """{"message":"Pay","year":2027,"month":1,"day":1,"hour":0,"minute":0,"in_minutes":2}"""

        assertThat(reminderAt(params)).isEqualTo(LocalDateTime.of(2026, 10, 7, 14, 32))
    }

    private fun reminder(parameters: String, log: (String) -> Unit = {}): AgentAction.ScheduleReminder =
        (AgentActionParser.parse("schedule_notification", parameters, now = clock, log = log) as ActionParse.Parsed).action as AgentAction.ScheduleReminder

    @Test
    fun `in_days with a time of day is that many days from today at that time`() {
        val tomorrowAtNine = reminder("""{"message":"Pay","in_days":1,"hour":9,"minute":0}""")

        assertThat(tomorrowAtNine.at).isEqualTo(LocalDateTime.of(2026, 10, 8, 9, 0))
        assertThat(tomorrowAtNine.offset).isEqualTo(ReminderOffset(days = 1, hours = 0, minutes = 0, atTime = true))
        assertThat(reminder("""{"message":"Pay","in_days":3,"hour":"18","minute":"45"}""").at).isEqualTo(LocalDateTime.of(2026, 10, 10, 18, 45))
        // The month rolls over.
        assertThat(reminder("""{"message":"Pay","in_days":30,"hour":9,"minute":0}""").at).isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0))
    }

    @Test
    fun `minutes or hours count from now even when a time of day is given`() {
        val inTwoHours = reminder("""{"message":"Pay","in_hours":2,"hour":9,"minute":0}""")

        assertThat(inTwoHours.at).isEqualTo(LocalDateTime.of(2026, 10, 7, 16, 30))
        assertThat(inTwoHours.offset).isEqualTo(ReminderOffset(days = 0, hours = 2, minutes = 0, atTime = false))
        assertThat(reminder("""{"message":"Pay","in_days":1,"in_minutes":5,"hour":9,"minute":0}""").at).isEqualTo(LocalDateTime.of(2026, 10, 8, 14, 35))
    }

    @Test
    fun `an offset from now keeps what the model said, and an absolute time has none`() {
        assertThat(reminder("""{"message":"Pay","in_minutes":2}""").offset).isEqualTo(ReminderOffset(0, 0, 2, atTime = false))
        assertThat(reminder("""{"message":"Pay","year":2026,"month":11,"day":2,"hour":9,"minute":30}""").offset).isNull()
    }

    @Test
    fun `in_days with a time of day that is not real is refused`() {
        assertThat(AgentActionParser.parse("schedule_notification", """{"message":"Pay","in_days":1,"hour":25,"minute":0}""", now = clock))
            .isInstanceOf(ActionParse.Rejected::class.java)
    }

    @Test
    fun `an offset that disagrees with a full absolute date wins and is logged`() {
        val lines = mutableListOf<String>()
        val params = """{"message":"Pay","year":2027,"month":1,"day":1,"hour":9,"minute":0,"in_days":1}"""

        assertThat(reminder(params, lines::add).at).isEqualTo(LocalDateTime.of(2026, 10, 8, 9, 0))
        assertThat(lines.single()).contains("disagrees")
    }

    @Test
    fun `an offset that agrees with the absolute date is not logged`() {
        val lines = mutableListOf<String>()

        reminder("""{"message":"Pay","year":2026,"month":10,"day":8,"hour":9,"minute":0,"in_days":1}""", lines::add)

        assertThat(lines).isEmpty()
    }

    @Test
    fun `an absolute time is unchanged by the clock`() {
        assertThat(reminderAt("""{"message":"Pay","year":2026,"month":11,"day":2,"hour":9,"minute":30}""")).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 30))
    }

    @Test
    fun `an offset of zero or less is refused`() {
        assertThat((AgentActionParser.parse("schedule_notification", """{"message":"Pay","in_minutes":0}""", now = clock) as ActionParse.Rejected).reason)
            .contains("more than zero")
        assertThat(AgentActionParser.parse("schedule_notification", """{"message":"Pay","in_hours":-1}""", now = clock)).isInstanceOf(ActionParse.Rejected::class.java)
    }

    @Test
    fun `a reminder falls back to the document of the chat`() {
        val action = parsed("schedule_notification", """{"message":"Pay","year":2026,"month":11,"day":2,"hour":9,"minute":0}""", doc = "chat-doc")

        assertThat((action as AgentAction.ScheduleReminder).documentId).isEqualTo("chat-doc")
    }

    @Test
    fun `a reminder with a missing or impossible part is refused`() {
        assertThat(rejected("schedule_notification", """{"message":"Pay","year":2026,"month":11,"day":2,"hour":9}""")).contains("minute")
        assertThat(rejected("schedule_notification", """{"message":"Pay","year":2026,"month":13,"day":2,"hour":9,"minute":0}""")).contains("real date")
        assertThat(rejected("schedule_notification", """{"year":2026,"month":11,"day":2,"hour":9,"minute":0}""")).contains("message")
    }

    @Test
    fun `get_current_date_and_time needs no parameters`() {
        assertThat(parsed("get_current_date_and_time", "")).isEqualTo(AgentAction.GetDateTime)
        assertThat(parsed("get_current_date_and_time", "{}")).isEqualTo(AgentAction.GetDateTime)
    }

    @Test
    fun `intents this app does not keep are refused`() {
        assertThat(rejected("send_sms", """{"phone_number":"1","sms_body":"x"}""")).contains("send_sms")
        assertThat(rejected("read_calendar_events", """{"date":"2026-11-05"}""")).contains("read_calendar_events")
        assertThat(rejected("run_js", "{}")).contains("run_js")
    }

    @Test
    fun `parameters that are not a JSON object are refused`() {
        assertThat(rejected("send_email", "to a@b.de")).contains("JSON")
        assertThat(rejected("send_email", "[1,2]")).contains("JSON")
    }

    @Test
    fun `the date and time text round-trips and says the weekday in English for the model`() {
        val at = LocalDateTime.of(2026, 10, 7, 14, 5)

        assertThat(ActionDateTime.format(at)).isEqualTo("2026-10-07 14:05")
        assertThat(ActionDateTime.parse(ActionDateTime.format(at))).isEqualTo(at)
        assertThat(ActionDateTime.parse("2026-10-07T14:05:59")).isEqualTo(at)
        assertThat(ActionDateTime.parse("7.10.2026 14:05")).isNull()
        assertThat(ActionDateTime.forModel(at.withSecond(9))).isEqualTo("2026-10-07T14:05:09 Wednesday")
    }
}
