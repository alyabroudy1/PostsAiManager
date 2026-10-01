package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.model.OcrBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The stages before the model, run on a fixture, so tests can look candidates up by kind and value. */
internal class Prepared(val pages: List<List<OcrBlock>>) {
    val layout: LetterLayout = LetterLayoutAnalyzer.analyze(pages)
    val candidates: CandidateSet = ExtractorCandidateSource().find(pages, layout)
    val offered: OfferedCandidates = CandidateTable.build(candidates)

    /** The offered candidate of [kind] whose value is [norm] (spaces ignored; a date matches a datetime of that day). */
    fun find(kind: CandidateKind, norm: String): Candidate? {
        val wanted = norm.filter { !it.isWhitespace() }.lowercase()
        return offered.rows.map { it.candidate }.firstOrNull { c ->
            val kinds = when (kind) {
                CandidateKind.DATE -> setOf(CandidateKind.DATE, CandidateKind.DATETIME)
                else -> setOf(kind)
            }
            if (c.kind !in kinds) return@firstOrNull false
            val have = c.normalized.filter { !it.isWhitespace() }.lowercase()
            if (kind == CandidateKind.DATE) have.startsWith(wanted) else have == wanted
        }
    }

    /**
     * A name candidate whose text is [name], ignoring case, spacing and accents; failing that the
     * shortest one that holds the name's words (a name candidate keeps the whole printed line, so
     * "Herrn Max Mustermann" is the candidate of "Max Mustermann").
     */
    fun findName(name: String): Candidate? {
        val names = offered.rows.map { it.candidate }.filter { it.kind == CandidateKind.NAME }
        val wanted = QuoteVerifier.fold(name).trim()
        return names.firstOrNull { QuoteVerifier.fold(it.raw).trim() == wanted }
            ?: names.filter { QuoteVerifier.fold(it.raw).contains(wanted) }.minByOrNull { it.raw.length }
    }
}

/** A model that answers with recorded JSON, so the verifier and everything after it is tested without a model. */
internal class ScriptedInterpreter(
    private val structured: String,
    private val text: String? = null,
) : DocumentInterpreter {
    override val maxAnswerTokens: Int = ModelDocumentInterpreter.MAX_ANSWER_TOKENS
    override val maxTextTokens: Int = ModelDocumentInterpreter.MAX_TEXT_TOKENS

    var interpretCalls = 0
        private set
    var textCalls = 0
        private set
    var lastRequest: InterpretationRequest? = null
        private set
    var lastTextRequest: TextRequest? = null
        private set

    override fun promptOverheadChars(offered: OfferedCandidates) = SelectionPrompt.overheadChars(ExtractionSchema.DEFAULT, offered, true)

    override fun textOverheadChars() = SelectionPrompt.textOverheadChars()

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        interpretCalls++
        lastRequest = request
        val grammar = StructuredGrammar.build(request.offered, ExtractionSchema.DEFAULT)
        return when (val parsed = InterpretationParser.parse(structured)) {
            // The single-call grammar has no topics; a test that wants some writes a `topics` array next to the answer, read here.
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value.copy(topics = topicsOf(structured)), structured, "", grammar)
            is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, structured, "", grammar)
        }
    }

    private fun topicsOf(json: String): List<String> =
        (Json.parseToJsonElement(json).jsonObject["topics"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    override suspend fun writeText(request: TextRequest): TextOutcome {
        textCalls++
        lastTextRequest = request
        val t = text ?: return TextOutcome.Failed("no scripted text")
        return when (val parsed = InterpretationParser.parseText(t)) {
            is InterpretationParser.Parsed.Ok -> TextOutcome.Written(parsed.value, t)
            is InterpretationParser.Parsed.Bad -> TextOutcome.Failed(parsed.reason)
        }
    }
}

/** Builds the JSON a correct model would answer for a [Letter], looking every id up in the prepared candidates. */
internal object Oracle {

    /** Expectations whose value is not among the candidates (a gap in the candidate stage, not in the model). */
    class Result(val json: String, val missing: List<String>)

    /** @param withTopics the letter's topics are answered too (and their slots filled); false answers the family's own slots only, as the generating interpreters can. */
    fun structured(letter: Letter, p: Prepared, withTopics: Boolean = true): Result {
        val missing = mutableListOf<String>()
        val topics = if (withTopics) letter.topics else emptyList()
        val root = buildJsonObject {
            put("type", letter.type.id)
            // The single-call grammar has no topics: the key is written only for the tests that read topics (see [ScriptedInterpreter]).
            if (withTopics) put("topics", buildJsonArray { topics.forEach { add(JsonPrimitive(it)) } })
            put("tc", "HIGH")
            put("lang", letter.language)
            put("parties", buildJsonArray { letter.parties.forEach { add(party(it, p)) } })
            put(
                "s",
                buildJsonObject {
                    for (slot in ExtractionSchema.DEFAULT.slotsFor(letter.type, topics)) {
                        val exp = letter.slots.firstOrNull { it.slot == slot }
                        val cand = exp?.takeIf { it.quote == null }?.let { p.find(it.kind, it.norm) }
                        if (exp != null && exp.quote == null && cand == null) missing += "${slot.json}=${exp.norm}"
                        put(slot.json, slotValue(slot, exp, cand))
                    }
                },
            )
            put(
                "x",
                buildJsonArray {
                    for (x in letter.extras) {
                        val cand = if (x.kind != null && x.norm != null) p.find(x.kind, x.norm) else null
                        if (x.kind != null && cand == null) missing += "extra ${x.label}=${x.norm}"
                        add(
                            buildJsonObject {
                                put("lb", x.label)
                                put("k", x.key)
                                put("id", cand?.id ?: "NONE")
                                put("v", if (cand == null) (x.quote ?: x.norm.orEmpty()) else "")
                                put("c", x.confidence)
                            },
                        )
                    }
                },
            )
        }
        return Result(root.toString(), missing)
    }

    fun text(letter: Letter): String = buildJsonObject {
        put("other", "")
        put("title", "${letter.manifest.sender}: ${letter.type.id}")
        put("subject", letter.subject ?: "NONE")
        put("summary", letter.summary ?: "")
        put("qs", buildJsonArray { repeat(3) { add(JsonPrimitive("Question ${it + 1}?")) } })
    }.toString()

    private fun party(e: ExpParty, p: Prepared): JsonElement {
        val cand = if (e.quote == null) p.findName(e.text) else null
        return buildJsonObject {
            put("r", e.role.name)
            put("id", cand?.id ?: e.quote ?: e.text)
            put("n", e.text)
            put("k", e.kind.name)
            put("rel", e.relation.name)
            put("c", "HIGH")
        }
    }

    private fun slotValue(slot: SlotKey, exp: ExpSlot?, cand: Candidate?): JsonElement {
        // A period in words has no candidate: the model quotes it as a rule.
        if (exp?.quote != null) {
            return buildJsonObject {
                put("rule", exp.quote)
                put("r", exp.role ?: "OTHER")
                put("c", "HIGH")
            }
        }
        if (exp == null || cand == null) return JsonPrimitive("NONE")
        return buildJsonObject {
            put("id", cand.id)
            if (slot.kind == SlotKind.AMOUNT || slot.kind == SlotKind.DATE || slot.kind == SlotKind.DEADLINE) {
                put("r", exp.role ?: "OTHER")
            }
            put("c", "HIGH")
        }
    }
}

internal fun JsonObject.withoutKey(key: String): JsonObject = JsonObject(filterKeys { it != key })

internal fun JsonArray.strings(): List<String> = map { (it as JsonPrimitive).content }
