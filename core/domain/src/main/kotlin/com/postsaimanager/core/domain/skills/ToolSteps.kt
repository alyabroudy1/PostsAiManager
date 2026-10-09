package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.model.ToolExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The kind of a step the model took while it replied. */
enum class ToolStepKind { LOAD_SKILL, RUN_INTENT, RUN_JS, OTHER }

/**
 * One tool call of a reply as the chat's progress panel shows it (the Gallery's `SkillProgressToolAction`): which tool, on what
 * ([subject]: the skill or the intent), the arguments worth showing ([detail]), whether it failed, and the webview a JS skill
 * asked to show.
 */
data class ToolStep(
    val kind: ToolStepKind,
    val subject: String,
    val detail: String,
    val failed: Boolean,
    val webview: JsSkillWebview? = null,
)

/**
 * Reads a reply's stored tool calls ([ToolExchange], kept with the reply) as steps for the panel. Pure: the panel renders them in
 * the app's own words (string resources); nothing here is user-facing text.
 */
object ToolSteps {

    private val json = Json { ignoreUnknownKeys = true }

    fun of(exchanges: List<ToolExchange>): List<ToolStep> = exchanges.map(::stepOf)

    /**
     * True when the reply's LAST `run_intent` call was refused: the action never reached a card, whatever the reply's words say.
     * A later call that went through (the model fixed it and tried again) settles it. Reads the stored calls only, never the text.
     */
    fun endsWithRefusedIntent(steps: List<ToolStep>): Boolean = steps.lastOrNull { it.kind == ToolStepKind.RUN_INTENT }?.failed == true

    private fun stepOf(exchange: ToolExchange): ToolStep {
        val args = objectOf(exchange.argumentsJson)
        val result = objectOf(exchange.resultJson)
        val failed = result["status"]?.text() == "failed" || result["error"] != null
        return when (exchange.name) {
            "load_skill" -> ToolStep(
                kind = ToolStepKind.LOAD_SKILL,
                subject = args["skill_name"]?.text().orEmpty(),
                detail = "",
                failed = result["skill_instructions"]?.text() == "Skill not found",
            )
            "run_intent" -> ToolStep(
                kind = ToolStepKind.RUN_INTENT,
                subject = args["intent"]?.text().orEmpty(),
                detail = args["parameters"]?.text().orEmpty(),
                failed = failed,
            )
            "run_js" -> ToolStep(
                kind = ToolStepKind.RUN_JS,
                subject = args["skill_name"]?.text().orEmpty(),
                detail = args["script_name"]?.text().orEmpty(),
                failed = failed,
                webview = JsSkillResults.webviewOfShown(exchange.shownJson),
            )
            else -> ToolStep(ToolStepKind.OTHER, exchange.name, "", failed)
        }
    }

    private fun objectOf(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun kotlinx.serialization.json.JsonElement.text(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}
