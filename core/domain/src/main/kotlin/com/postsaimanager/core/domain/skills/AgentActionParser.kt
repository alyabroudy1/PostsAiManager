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
 *   `document_id` (otherwise the document the chat is about).
 *
 * It reads shape only; whether the values are really in the letter is [ActionGrounding]'s job.
 */
object AgentActionParser {

    private val json = Json { isLenient = true }

    fun parse(intent: String, parameters: String, chatDocumentId: String? = null): ActionParse {
        val kind = AgentIntent.of(intent) ?: return ActionParse.Rejected("Intent not found: \"${intent.trim()}\"")
        if (kind == AgentIntent.GET_CURRENT_DATE_AND_TIME) return ActionParse.Parsed(AgentAction.GetDateTime)
        val params = runCatching { json.parseToJsonElement(parameters.ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return ActionParse.Rejected("The parameters are not a JSON object.")
        return when (kind) {
            AgentIntent.SEND_EMAIL -> email(params)
            AgentIntent.CREATE_CALENDAR_EVENT -> event(params)
            AgentIntent.SCHEDULE_NOTIFICATION -> reminder(params, chatDocumentId)
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

    private fun reminder(p: JsonObject, chatDocumentId: String?): ActionParse {
        val message = p.text("message") ?: return missing("message")
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
        return ActionParse.Parsed(AgentAction.ScheduleReminder(at = at, text = message, documentId = p.text("document_id") ?: chatDocumentId))
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
