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
import com.postsaimanager.core.domain.skills.JsSkillPaths
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.domain.skills.JsSkillResults
import com.postsaimanager.core.domain.skills.JsSkillWebview
import com.postsaimanager.core.domain.skills.SkillCatalog
import com.postsaimanager.core.model.ToolExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.util.UUID

/** What `load_skill` and `run_intent` do, and nothing LiteRT-LM: the tool classes delegate here. */
internal class AgentToolCalls(
    private val skills: SkillCatalog,
    private val context: ToolContext,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    /** Where the calls are noted (names and outcomes only, never the letter's content). */
    private val log: (String) -> Unit = {},
    /** Where `run_js` waits for the script's answer, which the app process delivers. */
    private val js: JsBroker = JsBroker(),
    /** How long a script may take; a script that does not answer in time fails the call, and the reply goes on. */
    private val jsTimeoutMs: Long = JS_TIMEOUT_MS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    /** `load_skill`: the skill's instructions, or "Skill not found". */
    fun loadSkill(skillName: String): Map<String, String> = runBlocking {
        val skill = skills.load(skillName)
        log("load_skill \"$skillName\": ${if (skill != null) "found" else "not found"}")
        mapOf("skill_name" to skillName, "skill_instructions" to (skill?.content() ?: "Skill not found"))
    }.also { record(LOAD_SKILL, mapOf("skill_name" to skillName), it) }

    /** `run_intent`: proposes the action (or answers the clock); never runs it. */
    fun runIntent(intent: String, parameters: String): Map<String, String> =
        propose(intent, parameters).also { record(RUN_INTENT, mapOf("intent" to intent, "parameters" to parameters), it) }

    /**
     * `run_js` (the Gallery's `RunJsTool`): runs a script of a JS skill and returns what it answered. The script runs in the app
     * process, in an offline WebView; this call publishes the request and waits for the answer. A webview the script asks for is
     * not told to the model (as in the Gallery): it is stored beside the call, for the chat to show.
     */
    fun runJs(skillName: String, scriptName: String, data: String): Map<String, String> {
        val script = scriptName.trim().ifEmpty { DEFAULT_SCRIPT }
        val arguments = mapOf("skill_name" to skillName, "script_name" to script, "data" to data)
        val skill = runBlocking { skills.load(skillName) }
        val failure = when {
            skill == null -> "Skill \"$skillName\" not found"
            !skill.isJsSkill -> "Skill \"${skill.name}\" has no script to run"
            script !in skill.scripts || JsSkillPaths.script(skill.folder, script) == null -> "Script \"$script\" not found in skill \"${skill.name}\""
            else -> null
        }
        if (failure != null || skill == null) {
            log("run_js \"$skillName/$script\": $failure")
            return mapOf("error" to failure.orEmpty(), "status" to "failed").also { record(RUN_JS, arguments, it) }
        }

        val request = JsSkillRequest(newRequestId(), skill.folder, script, data.trim().ifEmpty { "{}" })
        // Registered before the request goes out, so the answer cannot beat the wait.
        val answer = js.expect(request.id)
        log("run_js \"${skill.folder}/$script\": script requested")
        context.requestJs(request)
        val raw = try {
            runBlocking { js.await(request.id, answer, jsTimeoutMs) }
        } catch (e: CancellationException) {
            null
        }
        if (raw == null) {
            log("run_js \"${skill.folder}/$script\": no answer")
            return mapOf("error" to "The script did not answer", "status" to "failed").also { record(RUN_JS, arguments, it) }
        }

        val parsed = JsSkillResults.parse(raw)
        val webview = parsed.webview?.let { view ->
            JsSkillPaths.webview(skill.folder, view.url)?.let { path -> JsSkillWebview(path, view.aspectRatio) }
        }
        val scriptError = parsed.error
        val result = when {
            scriptError != null -> mapOf("error" to scriptError, "status" to "failed")
            !parsed.structured -> mapOf("result" to raw, "status" to "succeeded")
            else -> mapOf("result" to parsed.result.orEmpty(), "status" to "succeeded")
        }
        log("run_js \"${skill.folder}/$script\": ${result["status"]}${if (webview != null) ", with a webview" else ""}")
        record(RUN_JS, arguments, result, shown = webview?.let(JsSkillResults::shownJson).orEmpty())
        return result
    }

    /** Notes the call with its result, so the conversation can replay it as a tool-call turn (the model's own parameter names). */
    private fun record(tool: String, arguments: Map<String, String>, result: Map<String, String>, shown: String = "") {
        context.record(ToolExchange(tool, jsonObject(arguments), jsonObject(result), shown))
    }

    private fun jsonObject(values: Map<String, String>): String =
        JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()

    private fun propose(intent: String, parameters: String): Map<String, String> {
        val name = intent.trim()
        return when (val parsed = AgentActionParser.parse(name, parameters, context.chatDocumentId(), now(), log)) {
            is ActionParse.Rejected -> {
                log("run_intent \"$name\" rejected: ${parsed.reason} (parameters were: ${parameters.take(300)})")
                rejected(name, parsed.reason)
            }
            is ActionParse.Parsed -> when (parsed.action) {
                // Answered at once: it changes nothing and shares nothing, so there is no card.
                AgentAction.GetDateTime -> {
                    log("run_intent \"$name\" answered with the clock")
                    mapOf("action" to name, "result" to ActionDateTime.forModel(now()))
                }
                else -> {
                    // A reminder's parameters as the model sent them and what they became, so a wrong time can be traced to its
                    // cause. An email's or an event's carry the letter's content, so those are not logged.
                    val reminder = parsed.action as? AgentAction.ScheduleReminder
                    log(
                        if (reminder != null) "run_intent \"$name\" proposed: parameters ${parameters.take(300)} -> at ${reminder.at}, offset ${reminder.offset}"
                        else "run_intent \"$name\" proposed",
                    )
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
        /** The tools' names as the model calls them (LiteRT-LM turns the Kotlin method names into snake case). */
        const val LOAD_SKILL = "load_skill"
        const val RUN_INTENT = "run_intent"
        const val RUN_JS = "run_js"

        /** The Gallery's own default for a `run_js` call without a script name. */
        const val DEFAULT_SCRIPT = "index.html"

        /** A script is a small page; one that has not answered after this is stuck. */
        const val JS_TIMEOUT_MS = 60_000L

        /**
         * What the model is told after a proposal: the truth, and what to say, so that its one-sentence summary says the action
         * was prepared for the user's confirmation and not that it was done (the user may still cancel).
         */
        const val PROPOSED = "Not done yet: it is shown to the user as a card and waits for their confirmation. " +
            "Tell the user in one sentence that you prepared it and that they can confirm it on the card."
    }
}
