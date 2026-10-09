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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's tools/RunIntentTool.kt (v1.0.20). The tool is the same
// (name, description and parameters as the Gallery's), but it PROPOSES instead of running; see AgentToolCalls.

package com.postsaimanager.core.ai.litert.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * `run_intent`: the model's way to ask the app to act (write an e-mail, add a calendar event, set a reminder). It proposes the
 * action; the user opens it on a card in the chat, or does not.
 *
 * Internal to `:core:ai:litert`, like [LoadSkillTool].
 */
internal class RunIntentTool(private val calls: AgentToolCalls) : ToolSet {

    /** Run an Android intent */
    @Tool(
        description = "Run an Android intent. It is used to interact with the app to perform certain actions. " +
            "Read the skill with load_skill first; the call may be refused, and then nothing was done.",
    )
    fun runIntent(
        @ToolParam(
            description = "The intent to run: send_email, create_calendar_event, schedule_notification (a reminder) " +
                "or get_current_date_and_time.",
        ) intent: String,
        @ToolParam(
            description = "A JSON string with the parameters of the intent. schedule_notification: message, plus in_days with hour " +
                "and minute (tomorrow at 9 is in_days 1, hour 9, minute 0), or in_minutes / in_hours, or year, month, day, hour, minute. " +
                "send_email: extra_email, extra_subject, extra_text. create_calendar_event: title, begin_time (yyyy-MM-ddTHH:mm:ss).",
        )
        parameters: String,
    ): Map<String, String> = calls.runIntent(intent, parameters)
}
