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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's tools/LoadSkillTool.kt (v1.0.20). The Gallery's
// SkillsProvider becomes the app's SkillCatalog port (see AgentToolCalls), the ToolDefinition/ToolExecutionContext/
// SkillProgressToolAction machinery is not taken (the progress panel is a later phase), and the tool is a plain ToolSet.

package com.postsaimanager.core.ai.litert.tools

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * `load_skill`: hands the model the instructions of one skill. The model sees only names and descriptions up front (the system
 * prompt) and loads the one it needs.
 *
 * Internal to `:core:ai:litert`: LiteRT-LM finds the `@Tool` method by reflection (so R8 keeps it, see the app's proguard rules),
 * and no LiteRT-LM type leaves the module.
 */
internal class LoadSkillTool(private val calls: AgentToolCalls) : ToolSet {

    /** Loads skill. */
    @Tool(description = "Loads a skill.")
    fun loadSkill(
        @ToolParam(description = "The name of the skill to load.") skillName: String,
    ): Map<String, String> = calls.loadSkill(skillName)
}
