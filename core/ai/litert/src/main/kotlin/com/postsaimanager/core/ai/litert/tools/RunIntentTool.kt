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
        description = "Run an Android intent. It is used to interact with the app to perform certain actions.",
    )
    fun runIntent(
        @ToolParam(description = "The intent to run.") intent: String,
        @ToolParam(
            description = "A JSON string containing the parameter values required for the intent.",
        )
        parameters: String,
    ): Map<String, String> = calls.runIntent(intent, parameters)
}
