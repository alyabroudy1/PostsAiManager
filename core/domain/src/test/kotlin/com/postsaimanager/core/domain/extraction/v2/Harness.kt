package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.model.OcrBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    /** A name candidate whose text is [name], ignoring case, spacing and accents. */
    fun findName(name: String): Candidate? = offered.rows.map { it.candidate }.firstOrNull {
        it.kind == CandidateKind.NAME && QuoteVerifier.fold(it.raw).trim() == QuoteVerifier.fold(name).trim()
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
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value, structured, "", grammar)
            is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, structured, "", grammar)
        }
    }

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

    fun structured(letter: Letter, p: Prepared): Result {
        val missing = mutableListOf<String>()
        val root = buildJsonObject {
            put("type", letter.type.id)
            put("tc", "HIGH")
            put("lang", letter.language)
            put("parties", buildJsonArray { letter.parties.forEach { add(party(it, p)) } })
            put(
                "s",
                buildJsonObject {
                    for (slot in letter.type.slots) {
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
