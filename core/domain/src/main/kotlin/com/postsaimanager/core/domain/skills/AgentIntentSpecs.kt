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

// Modified by PostsAiManager: the Android intents google-ai-edge/gallery IntentHandler builds for send_email and
// create_calendar_event, described as plain data (the platform's action and extra names as strings) so they are testable on the
// JVM. The e-mail intent is ACTION_SENDTO with a mailto: URI (the Gallery uses ACTION_SEND), so only mail apps answer.

package com.postsaimanager.core.domain.skills

import java.time.ZoneId

/** One typed extra of an intent. */
sealed interface IntentExtra {
    data class Text(val value: String) : IntentExtra

    data class TextList(val values: List<String>) : IntentExtra

    data class Millis(val value: Long) : IntentExtra
}

/**
 * An Android intent as data: the platform's [action] string, the [data] URI and the [extras] by their platform key. The executor in
 * the app turns it into an `Intent`; nothing here touches Android.
 */
data class IntentSpec(
    val action: String,
    val data: String,
    val extras: Map<String, IntentExtra>,
)

/** Which intent opens each kind of action. Actions that open no other app (a reminder, the clock) have none. */
object AgentIntentSpecs {

    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val MAILTO = "mailto:"
    const val CALENDAR_EVENTS_URI = "content://com.android.calendar/events"
    const val EXTRA_EMAIL = "android.intent.extra.EMAIL"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val EXTRA_TITLE = "title"
    const val EXTRA_DESCRIPTION = "description"
    const val EXTRA_BEGIN_TIME = "beginTime"
    const val EXTRA_END_TIME = "endTime"

    fun of(action: AgentAction, zone: ZoneId = ZoneId.systemDefault()): IntentSpec? = when (action) {
        is AgentAction.SendEmail -> IntentSpec(
            action = ACTION_SENDTO,
            data = MAILTO,
            extras = mapOf(
                EXTRA_EMAIL to IntentExtra.TextList(listOf(action.to)),
                EXTRA_SUBJECT to IntentExtra.Text(action.subject),
                EXTRA_TEXT to IntentExtra.Text(action.body),
            ),
        )
        is AgentAction.CreateCalendarEvent -> IntentSpec(
            action = ACTION_INSERT,
            data = CALENDAR_EVENTS_URI,
            extras = buildMap {
                put(EXTRA_TITLE, IntentExtra.Text(action.title))
                put(EXTRA_DESCRIPTION, IntentExtra.Text(action.description))
                put(EXTRA_BEGIN_TIME, IntentExtra.Millis(action.start.atZone(zone).toInstant().toEpochMilli()))
                action.end?.let { put(EXTRA_END_TIME, IntentExtra.Millis(it.atZone(zone).toInstant().toEpochMilli())) }
            },
        )
        is AgentAction.ScheduleReminder, AgentAction.GetDateTime -> null
    }
}
