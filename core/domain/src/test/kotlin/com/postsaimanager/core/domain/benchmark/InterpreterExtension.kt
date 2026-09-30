package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.ZoneInterpreter
import com.postsaimanager.core.domain.extraction.zones.ZoneScoringInterpreter
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import com.postsaimanager.core.domain.extraction.v2.QuestionnairePrompt
import com.postsaimanager.core.testing.FakeAiEngine
import kotlinx.serialization.json.longOrNull
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.Letters
import com.postsaimanager.core.domain.extraction.v2.Oracle
import com.postsaimanager.core.domain.extraction.v2.Prepared
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationParser
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SelectionPrompt
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.SlotValue
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/*
 * The model stage of the benchmark: real OCR fixtures through the real v2 pipeline, with the model's
 * answers replayed from recordings.
 *
 * A recording is what a device run captured for one letter and one model variant, in
 * `src/test/resources/benchmark/recordings/<key>.<variant>.json`:
 *
 *     { "call1": "<raw text of call 1, the structured answer>",
 *       "call2": "<raw text of call 2, the free text>",        // optional
 *       "contextTokens": 4096 }                                // optional, default 4096
 *
 * Replaying runs the raw text through the same parser, verifier and adapter as production
 * ([ExtractionV2Pipeline]), so the numbers measure the model's judgement plus the checks. A recording
 * is tied to the candidate ids the extractor produced when it was made: after a change that moves
 * ids, re-record.
 */

/** One candidate of the table the model was offered when a run was recorded, so a replay can find it again by what it is. */
class RecordedCandidate(val id: String, val kind: String, val raw: String, val normalized: String, val page: Int)

/** One question of a questionnaire run: what was asked, what the model answered, and what it cost. */
class RecordedAsk(
    val name: String,
    val question: String,
    val answer: String?,
    val ms: Long = 0,
    val questionTokens: Int = -1,
    val answerTokens: Int = -1,
)

/** What a run cost on the device, as recorded. All optional: older recordings have none. */
class RecordedCost(
    val wallMs: Long? = null,
    val call1Ms: Long? = null,
    val call2Ms: Long? = null,
    val prefixTokens: Int? = null,
    val prefixMs: Long? = null,
)

/**
 * One recorded model run for one letter and one interpreter (variant): the single-call interpreter's two raw
 * answers ([call1], [call2]), or the questionnaire's [asks] in order, plus the offered candidates as they were
 * numbered then ([candidates], for remapping ids after the extractor moved them) and the cost.
 */
class Recording(
    val key: String,
    val variant: String,
    val call1: String,
    val call2: String?,
    val contextTokens: Int,
    val asks: List<RecordedAsk> = emptyList(),
    val candidates: List<RecordedCandidate> = emptyList(),
    val cost: RecordedCost = RecordedCost(),
) {
    val isQuestionnaire: Boolean get() = asks.isNotEmpty()
}

object Recordings {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every `<key>.<variant>.json` in [dir], sorted; empty when the directory is missing. */
    fun load(dir: File): List<Recording> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val stem = f.name.removeSuffix(".json")
            val key = stem.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            parse(key, stem.substringAfterLast('.'), f.readText())
        }

    /** One recording from its JSON, or null when it holds neither a `call1` nor `asks`. */
    fun parse(key: String, variant: String, text: String): Recording? {
        val o = json.parseToJsonElement(text).jsonObject
        val asks = (o["asks"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { el ->
            val a = el.jsonObject
            RecordedAsk(
                name = a["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                question = a["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                answer = a["answer"]?.jsonPrimitive?.contentOrNull,
                ms = a["ms"]?.jsonPrimitive?.longOrNull ?: 0,
                questionTokens = a["questionTokens"]?.jsonPrimitive?.intOrNull ?: -1,
                answerTokens = a["answerTokens"]?.jsonPrimitive?.intOrNull ?: -1,
            )
        }
        val call1 = o["call1"]?.jsonPrimitive?.contentOrNull ?: if (asks.isNotEmpty()) "" else return null
        val candidates = (o["candidates"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { el ->
            val c = el.jsonObject
            RecordedCandidate(
                id = c["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                kind = c["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                raw = c["raw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                normalized = c["normalized"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                page = c["page"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        return Recording(
            key, variant, call1,
            o["call2"]?.jsonPrimitive?.contentOrNull,
            o["contextTokens"]?.jsonPrimitive?.intOrNull ?: 4096,
            asks, candidates,
            RecordedCost(
                wallMs = o["wallMs"]?.jsonPrimitive?.longOrNull,
                call1Ms = o["call1Ms"]?.jsonPrimitive?.longOrNull,
                call2Ms = o["call2Ms"]?.jsonPrimitive?.longOrNull,
                prefixTokens = o["prefixTokens"]?.jsonPrimitive?.intOrNull,
                prefixMs = o["prefixMs"]?.jsonPrimitive?.longOrNull,
            ),
        )
    }
}

/**
 * Finds a recording's candidate ids in today's candidate table. A recording names candidates by id (`D3`),
 * and an id moves whenever the extractor finds another candidate before it; what a candidate *is* does not:
 * its kind, its normalised value and its page. So the old id maps to the offered candidate with the same
 * (kind, normalized, page), and an id with no such candidate is left as it was (it then fails the verifier
 * like any id the model invented, which is what a value the extractor no longer finds deserves).
 */
internal object IdRemap {

    fun between(recorded: List<RecordedCandidate>, offered: OfferedCandidates): Map<String, String> {
        if (recorded.isEmpty()) return emptyMap()
        fun key(kind: String, normalized: String, page: Int) = "$kind|$normalized|$page"
        val now = offered.rows.associate { row ->
            key(row.candidate.kind.name, row.candidate.normalized, row.pages.firstOrNull() ?: row.candidate.page) to row.candidate.id
        }
        return recorded.mapNotNull { r ->
            val current = now[key(r.kind, r.normalized, r.page)] ?: return@mapNotNull null
            if (current == r.id) null else r.id to current
        }.toMap()
    }

    private val QUOTED_ID = Regex("\"([A-Z][0-9]+)\"")

    /** JSON text (the single call's answer): every quoted id is replaced, all at once. */
    fun inJson(text: String, map: Map<String, String>): String =
        if (map.isEmpty()) text else QUOTED_ID.replace(text) { m -> map[m.groupValues[1]]?.let { "\"$it\"" } ?: m.value }

    /** A questionnaire answer: every bare word outside quotes that is an id, replaced, all at once. */
    fun inAnswer(text: String, map: Map<String, String>): String {
        if (map.isEmpty()) return text
        val out = StringBuilder()
        var i = 0
        var quoted = false
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                quoted = !quoted
                out.append(c)
                i++
            } else if (!quoted && c.isLetter()) {
                var j = i
                while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                out.append(if (word.length >= 2 && word[0] in 'A'..'Z' && word.drop(1).all { it.isDigit() }) map[word] ?: word else word)
                i = j
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** Replays a [Recording] as the model: parses the recorded raw answers exactly as [ModelDocumentInterpreter] parses live ones. */
class ScriptedInterpreter(private val recording: Recording) : DocumentInterpreter {
    override val maxAnswerTokens: Int = ModelDocumentInterpreter.MAX_ANSWER_TOKENS
    override val maxTextTokens: Int = ModelDocumentInterpreter.MAX_TEXT_TOKENS

    override fun promptOverheadChars(offered: OfferedCandidates): Int =
        SelectionPrompt.overheadChars(ExtractionSchema.DEFAULT, offered, recording.contextTokens >= SelectionPrompt.EXAMPLE_MIN_CONTEXT_TOKENS)

    override fun textOverheadChars(): Int = SelectionPrompt.textOverheadChars()

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        val grammar = StructuredGrammar.build(request.offered, ExtractionSchema.DEFAULT)
        val raw = IdRemap.inJson(recording.call1, IdRemap.between(recording.candidates, request.offered))
        return when (val parsed = InterpretationParser.parse(raw)) {
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value, raw, "", grammar)
            is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, raw.take(300), "", grammar)
        }
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        val text = recording.call2 ?: return TextOutcome.Failed("no recorded free text")
        return when (val parsed = InterpretationParser.parseText(text)) {
            is InterpretationParser.Parsed.Ok -> TextOutcome.Written(parsed.value, text)
            is InterpretationParser.Parsed.Bad -> TextOutcome.Failed(parsed.reason)
        }
    }
}

/**
 * A [PromptSession] that answers from a questionnaire recording, so the real [QuestionnaireInterpreter] (its
 * questions, its order, its parsing) is what replays. A question is matched by its text, which does not depend
 * on candidate ids; each recorded answer is used once, and its ids are remapped to today's candidate ids.
 */
internal class ReplayPromptSession(private val recording: Recording, private val remap: Map<String, String>) : PromptSession {
    private val used = HashSet<Int>()

    override suspend fun open(prefix: String): PamResult<Int> = PamResult.Success(recording.cost.prefixTokens ?: 0)

    override suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String> {
        // The live question text is followed by the chat template's closing of the turn; the recorded one is not.
        // Zone questions list their candidates with ids in the text: compared without them, so ids that moved still match.
        val text = withoutIds(question.removePrefix("\n\n"))
        val at = recording.asks.indices.firstOrNull { it !in used && !recording.asks[it].name.startsWith("score:") && text.startsWith(withoutIds(recording.asks[it].question)) }
            ?: return PamResult.Error(PamError.InferenceError("no recorded answer for this question"))
        used += at
        val answer = recording.asks[at].answer ?: return PamResult.Error(PamError.InferenceError("the recorded question failed"))
        return PamResult.Success(IdRemap.inAnswer(answer, remap))
    }

    /** A recorded scored batch (`score:*`): its questions in order, its answer the comma-separated scores. */
    override suspend fun score(continuations: List<String>, yes: String, no: String, shared: String): PamResult<List<Double>> {
        // A recording holds each question whole; the live one arrives as the shared level and the rest, read as one text.
        val asked = continuations.map { withoutIds((shared + it).removePrefix("\n\n")) }
        var live = asked
        // The batch as recorded; failing that, a subset of a recorded one in the same order (fewer type candidates than were recorded),
        // and failing that with the type descriptions as the recordings worded them.
        var picked: List<Int> = emptyList()
        fun find(subset: Boolean): Int? = recording.asks.indices.firstOrNull { i ->
            val a = recording.asks[i]
            if (i in used || !a.name.startsWith("score:")) return@firstOrNull false
            val questions = a.question.split(SCORE_SEPARATOR).map { withoutIds(it) }
            if (!subset && questions.size != live.size) return@firstOrNull false
            val found = ArrayList<Int>()
            var from = 0
            for (l in live) {
                val j = (from until questions.size).firstOrNull { l.startsWith(questions[it]) } ?: return@firstOrNull false
                found += j
                from = j + 1
            }
            picked = found
            true
        }
        var at = find(subset = false) ?: find(subset = true)
        if (at == null) {
            live = asked.map { asRecorded(it) }
            at = find(subset = false) ?: find(subset = true)
        }
        at ?: return PamResult.Error(PamError.InferenceError("no recorded scores for this batch"))
        used += at
        val answer = recording.asks[at].answer ?: return PamResult.Error(PamError.InferenceError("the recorded batch failed"))
        val scores = answer.split(',').map { it.trim().toDouble() }
        return PamResult.Success(picked.map { scores[it] })
    }

    /** A live question as the recording worded it: a type description reworded since the recording was made keeps its recorded scores. */
    private fun asRecorded(question: String): String = REWORDED.entries.fold(question) { text, (now, then) -> text.replace(now, then) }

    private fun withoutIds(text: String) = ID_TOKEN.replace(text, "#")

    private companion object {
        val ID_TOKEN = Regex("\\b[A-Z]{1,2}\\d{1,3}\\b")
        const val SCORE_SEPARATOR = "\n@@\n"

        /** Type descriptions as the recordings worded them, by today's wording (the recorded scores stand until the device re-records). */
        val REWORDED = mapOf(
            "Is this document ${ExtractionSchema.OTHER.description}? Answer:" to "Is this document anything that fits none of the other types? Answer:",
        )
    }

    override suspend fun close() = Unit

    override suspend fun countTokens(text: String): Int? = null
}

/** Replays a questionnaire [Recording] through the real [QuestionnaireInterpreter]. */
internal class QuestionnaireReplay(private val recording: Recording) : DocumentInterpreter {
    private var inner: QuestionnaireInterpreter? = null
    private val engine = FakeAiEngine()

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    private fun create(offered: OfferedCandidates?): QuestionnaireInterpreter {
        val remap = offered?.let { IdRemap.between(recording.candidates, it) } ?: emptyMap()
        return QuestionnaireInterpreter(
            engine, ReplayPromptSession(recording, remap), contextTokens = recording.contextTokens,
            restateOptions = recording.variant.startsWith("questionnaire2"),
        )
    }

    // The overheads are asked before and between the two calls: a throwaway instance answers them, so the one
    // that read the letter is still there for the free text.
    override fun promptOverheadChars(offered: OfferedCandidates): Int = create(null).promptOverheadChars(offered)

    override fun textOverheadChars(): Int = create(null).textOverheadChars()

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome =
        create(request.offered).also { inner = it }.interpret(request)

    override suspend fun writeText(request: TextRequest): TextOutcome =
        (inner ?: create(null)).writeText(request)
}

/**
 * Replays a zone recording (`zones`, or `zonesscoring` when [scoring] is given) through the real
 * [ZoneInterpreter] / [ZoneScoringInterpreter]: the same template match, zones, questions and parsing as
 * on the device, with the model's answers (or raw scores) coming from the recording. Scoring decisions are
 * made here with [scoring], so thresholds can be tuned on the recorded scores without a device.
 */
internal class ZoneReplay(private val recording: Recording, private val scoring: ScoringProfile?) : DocumentInterpreter {
    private var inner: DocumentInterpreter? = null
    private val engine = FakeAiEngine()

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    private fun create(offered: OfferedCandidates?): DocumentInterpreter {
        val remap = offered?.let { IdRemap.between(recording.candidates, it) } ?: emptyMap()
        val session = ReplayPromptSession(recording, remap)
        // A variant name with "ctx" in it (zonesctx, zonesscoringctx2b) was recorded with the neighbour glimpse.
        val ctx = recording.variant.contains("ctx")
        return if (scoring != null) {
            ZoneScoringInterpreter(engine, session, contextTokens = recording.contextTokens, profile = scoring, neighbourContext = ctx)
        } else {
            ZoneInterpreter(engine, session, contextTokens = recording.contextTokens, neighbourContext = ctx)
        }
    }

    override fun promptOverheadChars(offered: OfferedCandidates): Int = create(null).promptOverheadChars(offered)

    override fun textOverheadChars(): Int = create(null).textOverheadChars()

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome =
        create(request.offered).also { inner = it }.interpret(request)

    override suspend fun writeText(request: TextRequest): TextOutcome = (inner ?: create(null)).writeText(request)

    /** What the last scoring replay asked, with `answer == null` for a batch or question the recording had no answer for. */
    val transcript: List<com.postsaimanager.core.domain.extraction.v2.AskRecord>
        get() = (inner as? ZoneScoringInterpreter)?.transcript.orEmpty()
}

/**
 * Output-level noise: how many manifest `not_facts` values reach what the user sees. Slots are always
 * shown; an extra is shown unless its final confidence is below [ConfidenceCombiner.HIDDEN_BELOW]
 * (behind "Show all"), the same constant the screen reads.
 */
object ShownNoise {
    private val DIGITS = Regex("\\b\\d{5,}\\b")

    fun count(result: ExtractionV2Result, notFacts: String?): Int {
        val noise = notFacts?.let { DIGITS.findAll(it).map { r -> r.value }.toSet() }.orEmpty()
        if (noise.isEmpty()) return 0
        val shown = result.slots.values + result.slotLists.values.flatten() +
            result.extras.filter { it.value.confidence >= ConfidenceCombiner.HIDDEN_BELOW }.map { it.value }
        return shown.count { v ->
            val text = ExtractionBenchmark.squash(v.normalized) + " " + ExtractionBenchmark.squash(v.value)
            noise.any { text.contains(it) }
        }
    }
}

/** The oracle model (answers what the manifest says) through the real pipeline, for the letters that have one. */
object OracleRuns {
    fun shownNoise(m: ManifestDoc): Int? {
        val letter = Letters.all.firstOrNull { it.id == m.key } ?: return null
        val result = runBlocking {
            ExtractionV2Pipeline().run(
                letter.pages,
                com.postsaimanager.core.domain.extraction.v2.ScriptedInterpreter(Oracle.structured(letter, Prepared(letter.pages)).json, Oracle.text(letter)),
                4096,
            )
        }
        return ShownNoise.count(result, m.notFacts)
    }
}

class InterpreterScore(
    val variant: String,
    val docs: Int,
    /** Manifest `not_facts` values in the user-visible fields of the recorded runs. Gated at 0 in spirit; reported here. */
    val shownNoise: Int = 0,
    /** Manifest facts the deterministic stage found, that the result also holds (normalised value equal), over those facts. */
    val fieldMatch: Double,
    /** Sender and every addressee right, over documents with known roles. */
    val rolesMatch: Double,
    /**
     * Answers the checks dropped (an id outside the offered set, a quote not in the letter) plus accepted answers whose
     * value is in neither the candidates nor the OCR text, over all answers given. Lower is better.
     */
    val hallucination: Double,
    /** Verified open-metadata items per document. */
    val extrasPerDoc: Double,
    /**
     * The model's own confidence (LOW 0.4, MEDIUM 0.7, HIGH 0.9) bucket to the share of its answers in the bucket that equal a
     * manifest fact of the document, with the answer count. The manifest is not exhaustive, so this is a lower bound.
     */
    val calibration: Map<String, Pair<Double, Int>>,
    /** Mean wall time per letter on the device (prefill and every answer), from the recordings that carry one. */
    val secondsPerDoc: Double? = null,
    /** Questionnaire only: questions asked per letter, mean tokens of an answer, and mean seconds reading the prefix. */
    val questionsPerDoc: Double? = null,
    val answerTokensPerQuestion: Double? = null,
    val prefixSeconds: Double? = null,
    /** How often the choice was the first candidate shown, per question and per zone (see [FirstCandidateShare]). */
    val first: FirstShareReport? = null,
)

object InterpreterMetrics {

    private const val LOW = "0.0-0.5"
    private const val MID = "0.5-0.8"
    private const val HIGH = "0.8-1.0"

    /** Scores every variant found in [dir]; empty when there are no recordings (never an error). */
    fun scoreAll(docs: List<Pair<ManifestDoc, Fixture>>, dir: File): List<InterpreterScore> =
        Recordings.load(dir).groupBy { it.variant }.mapNotNull { (variant, recs) -> score(variant, docs, recs) }

    /** One recording replayed through the real interpreter of its variant, the real pipeline and the verifier. */
    fun replayResult(rec: Recording, f: Fixture, scoring: ScoringProfile = ScoringProfile()): ExtractionV2Result {
        val replay: DocumentInterpreter = when {
            rec.variant.startsWith(SCORING_VARIANT) -> ZoneReplay(rec, scoring)
            rec.variant.startsWith(ZONES_VARIANT) -> ZoneReplay(rec, null)
            rec.isQuestionnaire -> QuestionnaireReplay(rec)
            else -> ScriptedInterpreter(rec)
        }
        val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
        return runBlocking {
            ExtractionV2Pipeline().run(f.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height })
        }
    }

    /** The variants of the zone experiment: `zones`, `zonesscoring`, and either with a model suffix (`zonesscoring2b`). */
    const val ZONES_VARIANT = "zones"
    const val SCORING_VARIANT = "zonesscoring"

    /** @param scoring the abstain thresholds the scoring variants are decided with (the recordings hold raw scores). */
    fun score(
        variant: String,
        docs: List<Pair<ManifestDoc, Fixture>>,
        recordings: List<Recording>,
        scoring: ScoringProfile = ScoringProfile(),
    ): InterpreterScore? = score(variant, docs, recordings) { scoring }

    /** As above, with the thresholds chosen per letter (cross-fitted tuning: a letter is decided with thresholds tuned on the other fold). */
    fun score(
        variant: String,
        docs: List<Pair<ManifestDoc, Fixture>>,
        recordings: List<Recording>,
        scoringFor: (String) -> ScoringProfile,
    ): InterpreterScore? {
        val byKey = recordings.associateBy { it.key }
        var expectedFound = 0
        var matched = 0
        var given = 0
        var bad = 0
        var extras = 0
        var shownNoise = 0
        var scored = 0
        var roleDocs = 0
        var roleRight = 0
        var wallMs = 0L
        var walls = 0
        var asked = 0
        var answerTokens = 0L
        var answersCounted = 0
        var prefixMs = 0L
        var prefixes = 0
        val buckets = linkedMapOf(LOW to (0 to 0), MID to (0 to 0), HIGH to (0 to 0))

        for ((m, f) in docs) {
            val rec = byKey[m.key] ?: continue
            val det = ExtractionBenchmark.score(m, f)
            val pages = f.pages.map { it.blocks }
            val result = replayResult(rec, f, scoringFor(rec.key))
            scored++
            rec.cost.wallMs?.let { wallMs += it; walls++ }
            if (rec.isQuestionnaire) {
                asked += rec.asks.size
                rec.asks.filter { it.answerTokens >= 0 }.forEach { answerTokens += it.answerTokens; answersCounted++ }
                rec.cost.prefixMs?.let { prefixMs += it; prefixes++ }
            }

            val values = values(result)
            val expectations = det.facts.map { it.exp }
            // The model can only be judged on what the deterministic stage put in front of it.
            for (fact in det.facts.filter { it.found }) {
                expectedFound++
                if (values.any { Expectations.matchesValue(it.normalized, fact.exp) }) matched++
            }

            val text = ExtractionBenchmark.squash(pages.joinToString("\n") { p -> p.joinToString("\n") { it.text } })
            val candidateNorms = det.candidateSet.candidates.map { ExtractionBenchmark.squash(it.normalized) }.toSet()
            for (v in values) {
                given++
                val sq = ExtractionBenchmark.squash(v.normalized)
                val sv = ExtractionBenchmark.squash(v.value)
                if (v.candidateId == null && sq !in candidateNorms && !text.contains(sv)) bad++
            }
            bad += result.diagnostics.rejections.size
            given += result.diagnostics.rejections.size
            extras += result.extras.size
            shownNoise += ShownNoise.count(result, m.notFacts)

            for (v in values.filter { it.slot?.kind != SlotKind.ACTION }) {
                // The model's own word is one of LOW, MEDIUM, HIGH (or UNKNOWN when it wrote none): bucketed by the same numbers.
                val key = when {
                    v.aiConfidence < ConfidenceCombiner.UNKNOWN -> LOW
                    v.aiConfidence < ConfidenceCombiner.HIGH -> MID
                    else -> HIGH
                }
                val (n, k) = buckets.getValue(key)
                val ok = expectations.any { Expectations.matchesValue(v.normalized, it) }
                buckets[key] = (n + 1) to (k + if (ok) 1 else 0)
            }

            if (m.roles.addressees.isNotEmpty() || m.senderName != null) {
                roleDocs++
                fun fits(name: String, expected: String) =
                    ExtractionBenchmark.squash(name).contains(ExtractionBenchmark.squash(expected.substringBefore(',')))
                val senderOk = m.senderName == null || result.parties.sender?.name?.let { fits(it, m.senderName!!) } == true
                val names = result.parties.all.filter { it.role in ADDRESSED }.map { it.name }
                val addrOk = m.roles.addressees.all { a -> names.any { fits(it, a) } }
                if (senderOk && addrOk) roleRight++
            }
        }
        if (scored == 0) return null
        fun r(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
        return InterpreterScore(
            variant = variant,
            docs = scored,
            shownNoise = shownNoise,
            fieldMatch = r(matched, expectedFound),
            rolesMatch = r(roleRight, roleDocs),
            hallucination = if (given == 0) 0.0 else bad.toDouble() / given,
            extrasPerDoc = extras.toDouble() / scored,
            calibration = buckets.mapValues { (_, v) -> r(v.second, v.first) to v.first },
            secondsPerDoc = if (walls == 0) null else wallMs / 1000.0 / walls,
            questionsPerDoc = if (asked == 0) null else asked.toDouble() / scored,
            answerTokensPerQuestion = if (answersCounted == 0) null else answerTokens.toDouble() / answersCounted,
            prefixSeconds = if (prefixes == 0) null else prefixMs / 1000.0 / prefixes,
            first = FirstCandidateShare.of(recordings.filter { r -> docs.any { it.first.key == r.key } }),
        )
    }

    private val ADDRESSED = setOf(PartyRole.ADDRESSEE, PartyRole.CO_ADDRESSEE)

    /** Every value the result holds: slots, slot lists and extras (parties are scored as roles). */
    private fun values(result: ExtractionV2Result): List<SlotValue> =
        result.slots.values + result.slotLists.values.flatten() + result.extras.map { it.value }

    fun section(scores: List<InterpreterScore>): String {
        val sb = StringBuilder("## Interpreter (recorded model answers through the real v2 pipeline)\n\n")
        if (scores.isEmpty()) {
            sb.appendLine("No recordings (src/test/resources/benchmark/recordings/<key>.<variant>.json), nothing scored.")
            return sb.toString()
        }
        sb.appendLine("| variant | docs | shown noise | field match | roles | hallucination | extras/doc | calibration (accuracy, n) | s/doc | questions/doc, tok/answer, prefix s | first-candidate share |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in scores) {
            fun num(v: Double?, fmt: String) = if (v == null) "-" else String.format(Locale.ROOT, fmt, v)
            sb.appendLine(
                "| ${s.variant} | ${s.docs} | ${s.shownNoise} | ${pct(s.fieldMatch)} | ${pct(s.rolesMatch)} | ${pct(s.hallucination)} | " +
                    String.format(Locale.ROOT, "%.2f", s.extrasPerDoc) + " | " +
                    s.calibration.entries.joinToString(", ") { "${it.key}: ${pct(it.value.first)} (${it.value.second})" } + " | " +
                    num(s.secondsPerDoc, "%.1f") + " | " +
                    (if (s.questionsPerDoc == null) "-" else "${num(s.questionsPerDoc, "%.1f")}, ${num(s.answerTokensPerQuestion, "%.1f")}, ${num(s.prefixSeconds, "%.1f")}") + " | " +
                    (s.first?.overall?.text() ?: "-") + " |",
            )
        }
        return sb.toString()
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
}
