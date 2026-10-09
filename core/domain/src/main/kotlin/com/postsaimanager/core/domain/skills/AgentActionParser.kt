/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: the intent names and parameter names follow google-ai-edge/gallery IntentHandler (SendEmailParams,
// CreateCalendarEventParams, ScheduleNotificationParams); parsing is pure Kotlin (kotlinx.serialization instead of Moshi) and
// returns a typed AgentAction or a reason the model can act on.

package com.postsaimanager.core.domain.skills

import java.time.DateTimeException
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The intent names of a `run_intent` call, as the Gallery names them. */
enum class AgentIntent(val wire: String) {
    SEND_EMAIL("send_email"),
    CREATE_CALENDAR_EVENT("create_calendar_event"),
    SCHEDULE_NOTIFICATION("schedule_notification"),
    GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
    ;

    companion object {
        fun of(wire: String): AgentIntent? = entries.firstOrNull { it.wire == wire.trim() }

        /** The intent whose call proposes [action]. */
        fun of(action: AgentAction): AgentIntent = when (action) {
            is AgentAction.SendEmail -> SEND_EMAIL
            is AgentAction.CreateCalendarEvent -> CREATE_CALENDAR_EVENT
            is AgentAction.ScheduleReminder -> SCHEDULE_NOTIFICATION
            AgentAction.GetDateTime -> GET_CURRENT_DATE_AND_TIME
        }
    }
}

/** What a `run_intent` call turned into. */
sealed interface ActionParse {
    data class Parsed(val action: AgentAction) : ActionParse

    /** The call cannot become an action; [reason] is written for the model, so it can fix the call. */
    data class Rejected(val reason: String) : ActionParse
}

/**
 * Turns a `run_intent` call (an intent name and a JSON string of parameters, as the Gallery's `RunIntentTool` receives it) into an
 * [AgentAction]. Parameters (all strings in the JSON, except the numbers of a reminder, which may be numbers or digit strings):
 *
 * - `send_email`: `extra_email`, `extra_subject`, `extra_text`;
 * - `create_calendar_event`: `title`, `description`, `begin_time`, `end_time` (optional), as `yyyy-MM-ddTHH:mm:ss`;
 * - `schedule_notification`: `message` (and an ignored `title`), `year`, `month`, `day`, `hour`, `minute`, and optionally
 *   `document_id` (otherwise the document the chat is about). Instead of the date and time, a relative offset: `in_minutes`,
 *   `in_hours`, `in_days` (numbers, added together); the app adds it to the phone's clock. An offset of minutes or hours counts
 *   from now. An offset of only `in_days` together with `hour` and `minute` means that many days from today, at that time of day
 *   ("tomorrow at 9": `in_days` 1, `hour` 9, `minute` 0). When a full absolute date (`year`, `month`, `day`) is given as well and
 *   disagrees with the offset, the offset wins and the disagreement is logged.
 *
 * It reads shape only; whether the values are really in the letter is [ActionGrounding]'s job.
 */
object AgentActionParser {

    private val json = Json { isLenient = true }

    /** [now] is the phone's clock at proposal time; only a reminder's relative offset reads it. */
    fun parse(
        intent: String,
        parameters: String,
        chatDocumentId: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
        /** Where a reminder's disagreeing offset and date are noted. */
        log: (String) -> Unit = {},
        /** The documents a reminder may point at besides the chat's own: the model's `document_id` is used only when it is one of these. */
        knownDocumentIds: Set<String> = emptySet(),
    ): ActionParse {
        val kind = AgentIntent.of(intent)
            ?: return ActionParse.Rejected("Intent not found: \"${intent.trim()}\". The intents are: ${AgentIntent.entries.joinToString { it.wire }}.")
        if (kind == AgentIntent.GET_CURRENT_DATE_AND_TIME) return ActionParse.Parsed(AgentAction.GetDateTime)
        val params = runCatching { json.parseToJsonElement(parameters.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ActionParse.Rejected("The parameters are not a JSON object.")
        return when (kind) {
            AgentIntent.SEND_EMAIL -> email(params)
            AgentIntent.CREATE_CALENDAR_EVENT -> event(params)
            AgentIntent.SCHEDULE_NOTIFICATION ->
                when (val parsed = reminder(withDateAndTime(params), chatDocumentId, knownDocumentIds, now, log)) {
                    is ActionParse.Rejected -> ActionParse.Rejected(parsed.reason + REMINDER_FORMS)
                    is ActionParse.Parsed -> parsed
                }
            AgentIntent.GET_CURRENT_DATE_AND_TIME -> ActionParse.Parsed(AgentAction.GetDateTime)
        }
    }

    private fun email(p: JsonObject): ActionParse {
        val to = p.text("extra_email") ?: return missing("extra_email")
        return ActionParse.Parsed(AgentAction.SendEmail(to = to, subject = p.text("extra_subject").orEmpty(), body = p.text("extra_text").orEmpty()))
    }

    private fun event(p: JsonObject): ActionParse {
        val title = p.text("title") ?: return missing("title")
        val start = ActionDateTime.parse(p.text("begin_time") ?: return missing("begin_time")) ?: return badTime("begin_time")
        val end = p.text("end_time")?.let { ActionDateTime.parse(it) ?: return badTime("end_time") }
        return ActionParse.Parsed(AgentAction.CreateCalendarEvent(title = title, start = start, end = end, description = p.text("description").orEmpty()))
    }

    private fun reminder(
        p: JsonObject,
        chatDocumentId: String?,
        knownDocumentIds: Set<String>,
        now: LocalDateTime,
        log: (String) -> Unit,
    ): ActionParse {
        // A small model that did not read the skill names the line "description" or "text": the reminder's own line is the same thing.
        val message = p.text("message") ?: p.text("description") ?: p.text("text") ?: p.text("title") ?: return missing("message")
        // The model's document_id is never trusted on its own: a small model writes a reference number there. It counts only when it
        // is the chat's document or one the caller knows exists; otherwise the reminder belongs to the chat's document.
        val stated = p.text("document_id")
        val documentId = stated?.takeIf { it == chatDocumentId || it in knownDocumentIds } ?: chatDocumentId
        if (stated != null && stated != documentId) log("reminder: document_id \"${stated.take(40)}\" is not a known document; the chat's document is used")
        val offset = offset(p)
        if (offset != null) {
            val hour = p.number("hour")
            val minute = p.number("minute")
            val daysAtTime = offset.minutes == 0 && offset.hours == 0 && hour != null && minute != null
            if (offset.days < 0 || offset.hours < 0 || offset.minutes < 0 || (!daysAtTime && offset.isZero)) {
                return ActionParse.Rejected("in_minutes, in_hours and in_days must add up to more than zero.")
            }
            val at = if (daysAtTime) {
                try {
                    LocalDateTime.of(now.toLocalDate().plusDays(offset.days.toLong()), java.time.LocalTime.of(hour!!, minute!!))
                } catch (_: DateTimeException) {
                    return ActionParse.Rejected("hour and minute are not a real time of day.")
                }
            } else {
                now.truncatedTo(ChronoUnit.MINUTES).plus(offset.duration)
            }
            absolute(p)?.takeIf { it != at }?.let { log("reminder: the date the model gave ($it) disagrees with its offset ($at); the offset is used") }
            val understood = ReminderOffset(offset.days, offset.hours, offset.minutes, atTime = daysAtTime)
            return ActionParse.Parsed(AgentAction.ScheduleReminder(at = at, text = message, documentId = documentId, offset = understood))
        }
        val at = try {
            LocalDateTime.of(
                p.number("year") ?: return missing("year"),
                p.number("month") ?: return missing("month"),
                p.number("day") ?: return missing("day"),
                p.number("hour") ?: return missing("hour"),
                p.number("minute") ?: return missing("minute"),
            )
        } catch (_: DateTimeException) {
            return ActionParse.Rejected("year, month, day, hour and minute are not a real date and time.")
        }
        return ActionParse.Parsed(AgentAction.ScheduleReminder(at = at, text = message, documentId = documentId))
    }

    /**
     * The `date` and `time` spelling that a model which did not read the skill uses (`date` "today", "tomorrow" or `yyyy-MM-dd`,
     * `time` "HH:mm"), turned into the parameters the parser understands: today and tomorrow become `in_days` 0 / 1 with `hour` and
     * `minute`, a date its `year`, `month` and `day`. Only those forms, documented in the tool's own description; anything else is
     * left alone and answered with what to send. A parameter the model gave itself always wins.
     */
    private fun withDateAndTime(p: JsonObject): JsonObject {
        val time = TIME.matchEntire(p.text("time").orEmpty()) ?: return p
        val date = p.text("date").orEmpty().lowercase()
        val added = mutableMapOf<String, JsonPrimitive>()
        added["hour"] = JsonPrimitive(time.groupValues[1].toInt())
        added["minute"] = JsonPrimitive(time.groupValues[2].toInt())
        val iso = ISO_DATE.matchEntire(date)
        when {
            date == "today" -> added["in_days"] = JsonPrimitive(0)
            date == "tomorrow" -> added["in_days"] = JsonPrimitive(1)
            iso != null -> {
                added["year"] = JsonPrimitive(iso.groupValues[1].toInt())
                added["month"] = JsonPrimitive(iso.groupValues[2].toInt())
                added["day"] = JsonPrimitive(iso.groupValues[3].toInt())
            }
            else -> return p
        }
        return JsonObject(added.filterKeys { it !in p } + p)
    }

    private val TIME = Regex("(\\d{1,2}):(\\d{2})(?::\\d{2})?")
    private val ISO_DATE = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    /** What a refused reminder call tells the model to send instead. */
    private const val REMINDER_FORMS =
        " A reminder takes: message (one line), plus either in_days with hour and minute (tomorrow at 9: in_days 1, hour 9, minute 0), " +
            "in_minutes or in_hours (from now), or year, month, day, hour and minute (numbers). date (today, tomorrow or yyyy-MM-dd) " +
            "with time (HH:mm) is also accepted."

    /** The absolute date and time the model gave (a full year, month and day; hour and minute 0 when absent), or null when it gave none. */
    private fun absolute(p: JsonObject): LocalDateTime? {
        val year = p.number("year") ?: return null
        val month = p.number("month") ?: return null
        val day = p.number("day") ?: return null
        return try {
            LocalDateTime.of(year, month, day, p.number("hour") ?: 0, p.number("minute") ?: 0)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** What the model said in `in_minutes`, `in_hours` and `in_days` (a missing one is 0). */
    private data class Stated(val days: Int, val hours: Int, val minutes: Int) {
        val isZero: Boolean get() = days == 0 && hours == 0 && minutes == 0
        val duration: Duration get() = Duration.ofMinutes(minutes.toLong()).plusHours(hours.toLong()).plusDays(days.toLong())
    }

    /**
     * The relative offset the model stated ("in 2 minutes"), or null when it gave none. The model says how long; the app adds it
     * to the phone's clock, so the model never does date arithmetic. It wins over an absolute time when both are given.
     */
    private fun offset(p: JsonObject): Stated? {
        val minutes = p.number("in_minutes")
        val hours = p.number("in_hours")
        val days = p.number("in_days")
        if (minutes == null && hours == null && days == null) return null
        return Stated(days ?: 0, hours ?: 0, minutes ?: 0)
    }

    private fun missing(name: String) = ActionParse.Rejected("Missing parameter: $name")

    private fun badTime(name: String) = ActionParse.Rejected("$name must look like 2026-11-05T14:30:00 (a real date and time).")

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Int? = text(key)?.toDoubleOrNull()?.toInt()
}

/** The one reading and writing of the date-and-time text of an action: `yyyy-MM-dd HH:mm` on a card, also `T` and seconds when read. */
object ActionDateTime {

    private val SHAPE = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?")

    /** The date and time [text] spells, or null when it is not that shape or not a real date (31 February, hour 25). */
    fun parse(text: String): LocalDateTime? {
        val m = SHAPE.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return try {
            val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            LocalDateTime.of(date, java.time.LocalTime.of(h.toInt(), mi.toInt()))
        } catch (_: DateTimeException) {
            null
        }
    }

    /** What `get_current_date_and_time` answers, as the Gallery words it (`2026-10-07T14:30:00 Wednesday`), the weekday in English. */
    fun forModel(now: LocalDateTime): String = String.format(
        java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d %s",
        now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second,
        now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH),
    )

    /** Latin digits whatever the device language is, so what the card shows can be read back by [parse]. */
    fun format(dateTime: LocalDateTime): String = String.format(
        java.util.Locale.ROOT, "%04d-%02d-%02d %02d:%02d",
        dateTime.year, dateTime.monthValue, dateTime.dayOfMonth, dateTime.hour, dateTime.minute,
    )
}
