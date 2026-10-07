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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's skills/SkillExtensions.kt (getJsSkillUrl,
// getJsSkillWebviewUrl), tools/ToolAction.kt (CallJsToolAction, CallJsSkillResult and its webview part) and the way
// tools/RunJsTool.kt reads the script's answer (v1.0.20). Moshi becomes kotlinx.serialization; the Gallery's remote skill URLs
// and imported-folder bases are not taken: a script is only ever an asset of the bundled skill's own folder (`skills/<name>/`),
// so every URL here is relative to that folder and carries no host.

package com.postsaimanager.core.domain.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One `run_js` call the model made: run the script [scriptName] of the skill in the folder [skillFolder] with [data] (a JSON
 * string). It is published by the engine process and run, offline, by the app process ([JsSkillExecutor]); the answer goes back
 * under [id].
 */
data class JsSkillRequest(
    val id: String,
    val skillFolder: String,
    val scriptName: String,
    val data: String,
)

/**
 * Runs a JS skill's script in a sandbox with no network and no file access beyond the skill's own folder. The port of the app's
 * one offline WebView; the engine never runs JavaScript itself.
 */
interface JsSkillExecutor {

    /**
     * Runs the script and returns what its `ai_edge_gallery_get_result(data)` answered, the raw string (the Gallery's contract: a
     * JSON object with `result`, `error` and/or `webview`). A script that cannot be loaded, throws or does not answer in time
     * gives a JSON object with an `error`.
     */
    suspend fun run(request: JsSkillRequest): String
}

/** A webview a script asked the chat to show: [url] is relative to the skill's `assets/` folder, as in the Gallery. */
data class JsSkillWebview(val url: String, val aspectRatio: Float)

/** What a script answered, read from the JSON string it returned. */
data class JsSkillResult(
    val result: String?,
    val error: String?,
    val webview: JsSkillWebview?,
    /** True when the answer was a JSON object the contract knows; false when the whole string is the result. */
    val structured: Boolean,
)

/**
 * Reads a script's answer. The Gallery's rule (`RunJsTool`): a JSON object with `result`, `error`, `webview` or `image` is
 * structured; anything else is the result as it is. `image` (a base64 picture) is not taken: it would live in the chat's database.
 */
object JsSkillResults {

    private val json = Json { ignoreUnknownKeys = true }

    /** The Gallery's default for a webview without an aspect ratio: 4:3. */
    const val DEFAULT_ASPECT_RATIO = 4f / 3f

    fun parse(raw: String): JsSkillResult {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (obj == null || KNOWN_KEYS.none { it in obj }) return JsSkillResult(result = raw, error = null, webview = null, structured = false)
        val webview = obj["webview"]?.let(::webviewOf)
        return JsSkillResult(
            result = obj["result"]?.stringOrNull(),
            error = obj["error"]?.stringOrNull(),
            webview = webview,
            structured = true,
        )
    }

    private val KNOWN_KEYS = listOf("result", "error", "webview", "image")

    private fun webviewOf(element: JsonElement): JsSkillWebview? {
        val obj = element as? JsonObject ?: return null
        val url = obj["url"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val ratio = obj["aspectRatio"]?.jsonPrimitive?.doubleOrNull?.toFloat()?.takeIf { it > 0f } ?: DEFAULT_ASPECT_RATIO
        return JsSkillWebview(url, ratio)
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** The webview as the chat stores it beside the tool call ([com.postsaimanager.core.model.ToolExchange.shownJson]). */
    fun shownJson(webview: JsSkillWebview): String =
        JsonObject(
            mapOf(
                "webview" to JsonObject(mapOf("url" to JsonPrimitive(webview.url), "aspectRatio" to JsonPrimitive(webview.aspectRatio))),
            ),
        ).toString()

    /** The webview a stored `shownJson` holds, or null for none or anything that is not one. */
    fun webviewOfShown(shownJson: String): JsSkillWebview? {
        if (shownJson.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(shownJson).jsonObject }.getOrNull() ?: return null
        return obj["webview"]?.let(::webviewOf)
    }
}

/**
 * Where a skill's files are, as paths inside the app's bundled `skills/` folder. One owner of the layout the Gallery uses
 * (`scripts/<script>` run by the tool, `assets/<file>` shown by a webview), and of the rule that a path never leaves the skill.
 */
object JsSkillPaths {

    /** The folder every bundled skill lives in. */
    const val ROOT = "skills"

    /** The script to run, relative to [ROOT], or null when [skillFolder] or [scriptName] is not a plain name. */
    fun script(skillFolder: String, scriptName: String): String? =
        if (isPlainName(skillFolder) && isSafeRelative(scriptName)) "$skillFolder/scripts/${scriptName.trim()}" else null

    /**
     * The page a script's `webview.url` points at, relative to [ROOT]. As the Gallery does, a relative url means a file of the
     * skill's `assets/` folder; an absolute url (anything with a scheme) is refused, so a script cannot send the chat anywhere.
     */
    fun webview(skillFolder: String, url: String): String? {
        val trimmed = url.trim()
        if (!isPlainName(skillFolder) || trimmed.isEmpty() || SCHEME.containsMatchIn(trimmed) || trimmed.startsWith("//")) return null
        val relative = trimmed.removePrefix("/")
        return if (isSafeRelative(relative.substringBefore('?').substringBefore('#'))) "$skillFolder/assets/$relative" else null
    }

    /**
     * True for a path that stays inside its folder: no `..` segment, no empty or absolute start, no backslash or colon, no
     * encoded separator.
     */
    fun isSafeRelative(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("/") || p.contains('\\') || p.contains(':') || p.contains('%')) return false
        return p.split('/').none { it.isEmpty() || it == ".." || it == "." }
    }

    private fun isPlainName(name: String): Boolean = name.isNotBlank() && name.none { it == '/' || it == '\\' || it == ':' || it == '%' } && name != ".."

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}

/**
 * Carries the `run_js` calls of a chat reply from the engine to the sandbox and the script's answer back, for as long as the app
 * runs. It is the only thing between the two ports; it decides nothing.
 */
class RelayJsSkillsUseCase(
    private val requests: Flow<JsSkillRequest>,
    private val executor: JsSkillExecutor,
    private val deliver: suspend (id: String, result: String) -> Unit,
) {

    /** Collects until cancelled; each request runs on its own, so a slow script holds up no other. */
    suspend fun collect(scope: CoroutineScope) {
        requests.collect { request ->
            scope.launch {
                val answer = try {
                    executor.run(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JsonObject(mapOf("error" to JsonPrimitive("The script could not run: ${e.message.orEmpty()}"))).toString()
                }
                deliver(request.id, answer)
            }
        }
    }
}
