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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's tools/RunJsTool.kt (v1.0.20). The tool is the same (name,
// description and parameters as the Gallery's). Left out: skill secrets (DataStoreRepository, AskInfoToolAction), the `image` side
// effect and remote skill URLs. The script does not run here: it runs in the app process, in an offline WebView, and this process
// waits for its answer; see AgentToolCalls.runJs.

package com.postsaimanager.core.ai.litert.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * `run_js`: the model's way to run the script of a JavaScript skill. Internal to `:core:ai:litert`, like [LoadSkillTool].
 */
internal class RunJsTool(private val calls: AgentToolCalls) : ToolSet {

    /** Call JS skill */
    @Tool(description = "Runs JS script")
    fun runJs(
        @ToolParam(description = "The name of skill") skillName: String,
        @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user") scriptName: String,
        @ToolParam(description = "The data to pass to the script. Use empty string if not provided by user") data: String,
    ): Map<String, String> = calls.runJs(skillName, scriptName, data)
}
