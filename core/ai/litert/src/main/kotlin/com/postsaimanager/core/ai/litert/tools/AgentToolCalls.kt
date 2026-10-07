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

// Modified by PostsAiManager: the bodies of the Google AI Edge Gallery's tools/LoadSkillTool.kt and tools/RunIntentTool.kt (v1.0.20).
// The Gallery's run_intent RUNS the Android intent (IntentHandler.handleAction, with a permission prompt) because its built-in tools
// are `alwaysAllow`. Here it only PROPOSES: it checks the call with the app's pure AgentActionParser, publishes it on the action
// channel and tells the model that the user must confirm on a card. Nothing runs here, and the :inference process has no Activity
// to run it in anyway. The logic is a plain class so it is tested without the LiteRT-LM library (whose classes need a newer JVM
// than the unit tests run on); LoadSkillTool and RunIntentTool only carry LiteRT-LM's annotations.

package com.postsaimanager.core.ai.litert.tools

import com.postsaimanager.core.domain.skills.ActionDateTime
import com.postsaimanager.core.domain.skills.ActionParse
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.AgentActionParser
import com.postsaimanager.core.domain.skills.AgentIntent
import com.postsaimanager.core.domain.skills.SkillCatalog
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime

/** What `load_skill` and `run_intent` do, and nothing LiteRT-LM: the tool classes delegate here. */
internal class AgentToolCalls(
    private val skills: SkillCatalog,
    private val context: ToolContext,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {

    /** `load_skill`: the skill's instructions, or "Skill not found". */
    fun loadSkill(skillName: String): Map<String, String> = runBlocking {
        val skill = skills.load(skillName)
        mapOf("skill_name" to skillName, "skill_instructions" to (skill?.content() ?: "Skill not found"))
    }

    /** `run_intent`: proposes the action (or answers the clock); never runs it. */
    fun runIntent(intent: String, parameters: String): Map<String, String> {
        val name = intent.trim()
        return when (val parsed = AgentActionParser.parse(name, parameters, context.chatDocumentId())) {
            is ActionParse.Rejected -> rejected(name, parsed.reason)
            is ActionParse.Parsed -> when (parsed.action) {
                // Answered at once: it changes nothing and shares nothing, so there is no card.
                AgentAction.GetDateTime -> mapOf("action" to name, "result" to ActionDateTime.forModel(now()))
                else -> {
                    context.propose(name, parameters)
                    mapOf("action" to name, "status" to PROPOSED)
                }
            }
        }
    }

    /**
     * The reason goes to the model so it can fix the call. As the Gallery's `guardMissingEntityWithSkillFallback` does, a skill's
     * name used as an intent gets the hint to load it as a skill.
     */
    private fun rejected(name: String, reason: String): Map<String, String> {
        val unknownIntent = AgentIntent.of(name) == null
        val isSkill = unknownIntent && runBlocking { skills.load(name) } != null
        val error = if (isSkill) "Intent not found. Try to run it as a skill" else reason
        return mapOf("error" to error, "status" to "failed")
    }

    internal companion object {
        /** What the model is told after a proposal: the user decides, so it must not claim the action is done. */
        const val PROPOSED = "proposed to the user, waiting for their confirmation on the card"
    }
}
