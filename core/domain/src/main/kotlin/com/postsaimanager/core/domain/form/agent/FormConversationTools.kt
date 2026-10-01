package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentTool
import com.postsaimanager.core.domain.agent.ToolParams
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.model.FormFillStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ask_user(question, chips)`: shows the question in the chat, with the chips as tappable answers, and ENDS the turn. The reply
 * (a tapped chip or typed text) comes back as the next user message. The model writes the question and the chips in the user's
 * language; code only checks they are shown sensibly.
 */
class AskUserTool : AgentTool {
    override val name = NAME
    override val description = "Asks the user one question and waits for the reply. Chips are answer buttons for a short choice."
    override val parameters: JsonObject = ToolParams.schema(
        ToolParams.string("question", ""),
        ToolParams.stringArray("chips", "At most $MAX_CHIPS.", required = false),
    )
    override val endsTurn = true

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val question = args.string("question").orEmpty().trim()
        if (question.isEmpty()) return ToolResult.error("the question is empty")
        if (question.length > MAX_QUESTION_CHARS) return ToolResult.error("the question is too long: keep it under $MAX_QUESTION_CHARS characters")
        val chips = args.strings("chips").orEmpty().map { it.trim() }
        if (chips.size > MAX_CHIPS) return ToolResult.error("at most $MAX_CHIPS chips")
        if (chips.any { it.isEmpty() || it.length > MAX_CHIP_CHARS }) return ToolResult.error("every chip needs 1 to $MAX_CHIP_CHARS characters")
        if (chips.map { it.lowercase() }.toSet().size != chips.size) return ToolResult.error("the chips must all be different")
        return ToolResult.ok("shown" to JsonPrimitive(true))
    }

    companion object {
        const val NAME = "ask_user"
        const val MAX_CHIPS = 6
        const val MAX_CHIP_CHARS = 60
        const val MAX_QUESTION_CHARS = 400
    }
}

/** `show_fill_card()`: the live card of every field and its value appears in the chat (it always shows the latest state). */
class ShowFillCardTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Shows the fill card (every field and its value) in the chat."
    override val parameters: JsonObject = ToolParams.schema()

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val fields = env.fields()
        if (fields.isEmpty()) return ToolResult.error("no form is read yet: call read_form first")
        val progress = FillProgress.of(fields)
        return ToolResult.ok(
            buildJsonObject {
                put("ready", progress.ready)
                put("total", progress.total)
                put("need_the_user", progress.needYou)
                put("signatures", progress.signatures)
            },
        )
    }

    companion object {
        const val NAME = "show_fill_card"
    }
}

/** `show_on_page(field_id)`: a chip in the chat that opens the page with the field's box marked. */
class ShowOnPageTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Shows a button that opens the page with one field marked (for example the signature)."
    override val parameters: JsonObject = ToolParams.schema(ToolParams.string("field_id", ""))

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val fields = env.fields()
        val field = FormRefs.findField(fields, args.string("field_id").orEmpty()) ?: return ToolResult.error("unknown field_id; use an id from read_form")
        return ToolResult.ok("page" to JsonPrimitive(field.page))
    }

    companion object {
        const val NAME = "show_on_page"
    }
}

/** `finish(summary)`: the form is done for now; the summary is the last message and the fill is marked done. ENDS the turn. */
class FinishTool(private val env: FormToolEnv) : AgentTool {
    override val name = NAME
    override val description = "Ends the filling with a short closing message: what is ready and what is left to do by hand."
    override val parameters: JsonObject = ToolParams.schema(ToolParams.string("summary", ""))
    override val endsTurn = true

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val summary = args.string("summary").orEmpty().trim()
        if (summary.isEmpty()) return ToolResult.error("the summary is empty")
        val fill = env.fill() ?: return ToolResult.error("no form is read yet: call read_form first")
        env.fills.saveFill(fill.copy(status = FormFillStatus.DONE, awaiting = null, currentFieldId = null, updatedAt = env.clock()))
        return ToolResult.ok()
    }

    companion object {
        const val NAME = "finish"
    }
}
