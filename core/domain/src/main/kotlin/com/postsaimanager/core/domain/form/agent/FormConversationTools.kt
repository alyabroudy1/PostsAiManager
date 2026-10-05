package com.postsaimanager.core.domain.form.agent

import com.postsaimanager.core.domain.agent.AgentContext
import com.postsaimanager.core.domain.agent.AgentEntry
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.agent.AgentTool
import com.postsaimanager.core.domain.agent.ToolParams
import com.postsaimanager.core.domain.agent.ToolResult
import com.postsaimanager.core.domain.agent.string
import com.postsaimanager.core.domain.agent.strings
import com.postsaimanager.core.domain.form.fill.FillProgress
import com.postsaimanager.core.model.FormFillStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `ask_user(question, chips)`: shows the question in the chat, with the chips as tappable answers, and ENDS the turn. The reply
 * (a tapped chip or typed text) comes back as the next user message. The model writes the question and the chips in the user's
 * language; code only checks they are shown sensibly.
 */
class AskUserTool(private val env: FormToolEnv, private val guidance: FormGuidance, private val guard: QuestionGuard) : AgentTool {
    override val name = NAME
    override val description = "Asks the user one question and waits for the reply. Chips are answer buttons for a short choice."
    override val parameters: JsonObject = ToolParams.schema(
        ToolParams.string("question", ""),
        ToolParams.stringArray("chips", "At most $MAX_CHIPS.", required = false),
    )
    override val endsTurn = true

    /** A chip is a label the user reads: a person's `p1` (an internal id) becomes the name; the other ids are refused in [execute]. */
    override suspend fun normalize(args: JsonObject): JsonObject {
        val chips = args.strings("chips") ?: return args
        val people = env.managed()
        val named = chips.map { chip -> if (PERSON_ID.matches(chip.trim())) FormRefs.findPerson(people, chip)?.name ?: chip else chip }
        val unique = named.distinctBy { FormRefs.fold(it) }
        return JsonObject(args + ("chips" to JsonArray(unique.map(::JsonPrimitive))))
    }

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        val question = args.string("question").orEmpty().trim()
        if (question.isEmpty()) return ToolResult.error("the question is empty")
        if (question.length > MAX_QUESTION_CHARS) return ToolResult.error("the question is too long: keep it under $MAX_QUESTION_CHARS characters")
        val chips = args.strings("chips").orEmpty().map { it.trim() }
        if (chips.size > MAX_CHIPS) return ToolResult.error("at most $MAX_CHIPS chips")
        if (chips.any { it.isEmpty() || it.length > MAX_CHIP_CHARS }) return ToolResult.error("every chip needs 1 to $MAX_CHIP_CHARS characters")
        if (chips.map { it.lowercase() }.toSet().size != chips.size) return ToolResult.error("the chips must all be different")
        chips.firstOrNull { ID_LIKE.matches(it) }?.let { id ->
            val names = env.managed().joinToString(", ") { it.name }
            return ToolResult.error("the chip \"$id\" is an internal id, not an answer the user can read: write the person's name or the option itself (people: $names)")
        }
        wrongScript(question, context)?.let { return ToolResult.error(it) }
        wrongLanguage(question, context)?.let { return ToolResult.error(it) }
        roleNameStep(question, chips, context)?.let { return ToolResult.error(it) }
        guard.check(question, chips)?.let { return ToolResult.error(it) }
        alreadyAnswered(question, chips, context)?.let { answer ->
            val suggestion = guidance.suggestionFor(UserReply.of(context))
            return ToolResult.error("already answered: $answer. Use it: suggested next: $suggestion")
        }
        return ToolResult.ok("shown" to JsonPrimitive(true))
    }

    /**
     * An error when a question is plainly English on a form that is not: at least half of its words are English function words
     * ([EnglishFunctionWords], a negative signal only) and none of its words is in the form's vocabulary (its labels, headings, printed
     * options and the document's stored OCR text). A question that shares even one word with the form, or whose function words also
     * occur in the OCR text, is in the form's language and passes. A question the model repeats unchanged after this refusal is let
     * through.
     */
    private suspend fun wrongLanguage(question: String, context: AgentContext): String? {
        val language = env.formLanguage()
        if (language.language == EnglishFunctionWords.LANGUAGE) return null
        if (WritingScript.dominant(question) != WritingScript.of(language)) return null
        val all = wordsOf(question).toList()
        if (all.size < MIN_QUESTION_WORDS || !EnglishFunctionWords.mostlyEnglish(all)) return null
        val flat = FormRefs.flat(question)
        if (context.turnCalls.any { it.name == NAME && FormRefs.flat(it.args.string("question").orEmpty()) == flat }) return null
        val vocabulary = env.fields().flatMap { listOfNotNull(it.labelText, it.section) + it.options }.flatMap(::wordsOf).filter { it.length >= MIN_WORD_CHARS }.toSet() +
            env.ocrWords()
        if (all.any { it in vocabulary }) return null
        val name = language.getDisplayLanguage(java.util.Locale.ENGLISH)
        return "the question is not written in $name: write it in $name"
    }

    /**
     * The error for a model that, after the user chose "someone else" for a role, asks the role question again instead of the person's
     * name: the question (or its chips) repeats the role question. A new question passes. The third time the result also carries the
     * question to ask, in the form's language (still the model's call: it is only an example).
     */
    private suspend fun roleNameStep(question: String, chips: List<String>, context: AgentContext): String? {
        val reply = UserReply.of(context) ?: return null
        val asked = reply.asked ?: return null
        val hint = guidance.nameNeeded(reply) ?: return null
        val earlierChips = asked.args.strings("chips").orEmpty().map { it.lowercase() }.toSet()
        val sameChips = chips.isNotEmpty() && (chips.map { it.lowercase() }.toSet() == earlierChips || chips.any { FormRefs.fold(it) == FormRefs.fold(env.roles.someoneElse()) })
        val similar = jaccard(wordsOf(question), wordsOf(asked.args.string("question").orEmpty())) >= SAME_ROLE_QUESTION
        if (!sameChips && !similar) return null
        val repeats = context.turnCalls.count { it.name == NAME }
        if (repeats < MAX_ROLE_REPEATS) return "already asked who it is: $hint"
        val example = env.roles.personNameQuestion()
        return "already asked who it is: $hint tools_now: ask_user. Ask: \"$example\" (in ${env.formLanguage().getDisplayLanguage(java.util.Locale.ENGLISH)}, no chips)"
    }

    /**
     * An error when the question is not in the script of the form's language (nor of what the user wrote). A language of the same script
     * cannot be told apart by code: the instructions and every state line ask for the form's language.
     */
    private suspend fun wrongScript(question: String, context: AgentContext): String? {
        val language = env.formLanguage()
        val allowed = setOf(WritingScript.of(language)) + context.userReplies.mapNotNull(WritingScript::dominant)
        val used = WritingScript.dominant(question) ?: return null
        if (used in allowed) return null
        val name = language.getDisplayLanguage(java.util.Locale.ENGLISH)
        return "the question is not written in $name: write it in $name"
    }

    /**
     * The answer to the earlier question of this run that [question] repeats, or null. Not a repeat while nobody is registered as the subject
     * (the answer did not name a person: asking again, with the people as chips, is what is needed).
     */
    private suspend fun alreadyAnswered(question: String, chips: List<String>, context: AgentContext): String? {
        // Nobody is registered as the subject yet, or the name of a role's person is what is asked now: a question that is not the role
        // question again is a new one ([roleNameStep] already refused the repeats).
        val stage = guidance.stage(env.fields(), UserReply.of(context))
        if (stage == FormStage.SUBJECT_UNKNOWN || stage == FormStage.ROLE_NAME_NEEDED) return null
        return earlierAnswer(question, chips, context)
    }

    private fun earlierAnswer(question: String, chips: List<String>, context: AgentContext): String? {
        val entries = context.entries
        val words = wordsOf(question)
        val chipSet = chips.map { it.lowercase() }.toSet()
        entries.forEachIndexed { index, entry ->
            val answer = entries.getOrNull(index + 1) as? AgentEntry.UserText ?: return@forEachIndexed
            if (entry !is AgentEntry.Call || entry.name != NAME || answer.isStart) return@forEachIndexed
            val earlierWords = wordsOf(entry.args.string("question").orEmpty())
            val similarity = jaccard(words, earlierWords)
            val earlierChips = entry.args.strings("chips").orEmpty().map { it.lowercase() }.toSet()
            val sameChips = chipSet.size >= 2 && chipSet == earlierChips
            if (similarity >= SAME_QUESTION || (sameChips && similarity >= SAME_QUESTION_SAME_CHIPS)) return answer.text
        }
        return null
    }

    private fun wordsOf(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(QuoteVerifier.fold(text)).map { it.value }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    companion object {
        /** How alike two questions' words must be to be the same question; with the same chips, less is enough. */
        private const val SAME_QUESTION = 0.7
        private const val SAME_QUESTION_SAME_CHIPS = 0.34

        /** What a model writes when it copies a reference (`p1`, `f3`) instead of a label. */
        private val PERSON_ID = Regex("(?i)p\\d+")
        private val ID_LIKE = Regex("(?i)[pf]\\d+")
        private const val MIN_WORD_CHARS = 3
        private const val MIN_QUESTION_WORDS = 3

        /** How alike a question must be to the role question to count as asking it again. */
        private const val SAME_ROLE_QUESTION = 0.6

        /** After this many refused role questions in a turn, the result carries the question to ask. */
        private const val MAX_ROLE_REPEATS = 2

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
