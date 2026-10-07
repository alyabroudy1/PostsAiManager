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

// Modified by PostsAiManager: adapted from google-ai-edge/gallery IntentHandler.handleAction. The intent is built by the pure
// AgentIntentSpecs (e-mail through ACTION_SENDTO with mailto:, calendar through ACTION_INSERT); a reminder goes through the app's
// own ReminderScheduler instead of the Gallery's notification scheduler; send_sms and read_calendar_events are not taken.

package com.postsaimanager.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.postsaimanager.core.domain.skills.ActionDateTime
import com.postsaimanager.core.domain.skills.ActionResult
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.AgentActionExecutor
import com.postsaimanager.core.domain.skills.AgentIntentSpecs
import com.postsaimanager.core.domain.skills.IntentExtra
import com.postsaimanager.core.domain.skills.IntentSpec
import com.postsaimanager.core.domain.skills.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Does an [AgentAction] in the main process. Mail and calendar are opened for the user to finish (the composer's Send, the calendar's
 * Save): the app never sends a mail or writes the calendar itself, so it needs no permission for either.
 */
@Singleton
class AndroidAgentActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminders: ReminderScheduler,
) : AgentActionExecutor {

    override suspend fun execute(action: AgentAction): ActionResult = when (action) {
        is AgentAction.ScheduleReminder ->
            if (reminders.schedule(action.at, action.text, action.documentId)) ActionResult.Succeeded() else ActionResult.Failed("The reminder could not be scheduled.")
        AgentAction.GetDateTime -> ActionResult.Succeeded(ActionDateTime.forModel(LocalDateTime.now()))
        is AgentAction.SendEmail, is AgentAction.CreateCalendarEvent -> open(action)
    }

    private fun open(action: AgentAction): ActionResult {
        val spec = AgentIntentSpecs.of(action) ?: return ActionResult.Failed("Nothing to open for this action.")
        return try {
            context.startActivity(intentOf(spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult.Succeeded()
        } catch (_: ActivityNotFoundException) {
            ActionResult.Failed("No app on this phone can handle it.")
        } catch (e: SecurityException) {
            ActionResult.Failed(e.message ?: "Not allowed.")
        }
    }

    internal companion object {
        /** The platform intent a [spec] describes. */
        fun intentOf(spec: IntentSpec): Intent = Intent(spec.action, Uri.parse(spec.data)).apply {
            for ((key, extra) in spec.extras) {
                when (extra) {
                    is IntentExtra.Text -> putExtra(key, extra.value)
                    is IntentExtra.TextList -> putExtra(key, extra.values.toTypedArray())
                    is IntentExtra.Millis -> putExtra(key, extra.value)
                }
            }
        }
    }
}
