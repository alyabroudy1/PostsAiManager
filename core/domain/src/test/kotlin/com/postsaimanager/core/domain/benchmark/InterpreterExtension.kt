package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.actions.ActionQuestions
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.domain.extraction.zones.QuestionNames
import com.postsaimanager.core.domain.extraction.zones.ScoringDescriptions
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
            // A type id of a recording made before the families is read as the family (and topics) it became (LegacyTypes).
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(
                parsed.value.let { r -> LegacyTypes.of(r.type)?.let { m -> r.copy(type = m.family, topics = m.topics) } ?: r }, raw, "", grammar,
            )
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

    /**
     * What the live reading asked that the recording holds no answer for, one line each (the question's first words; never an id). A
     * recording made for the current interpreter must have none ([ZoneReplay.requireComplete]); one made before extraction-v2-2 lacks the
     * questions that did not exist then (the address labels, the summary, a slot the old type never asked), and its family and topics are
     * answered explicitly from its legacy type scores ([LegacyFamilyBridge]).
     */
    val misses = ArrayList<String>()

    /**
     * The party and slot questions (a batch of values under one role or slot statement) the recording never held: the layout only orders
     * the candidates now, so a question is asked over every candidate of the page, and a question a template used to skip (the contact person
     * under a template with no information block) or a block it used to show differently was never recorded. Replayed as "not recorded"
     * (no threshold accepts that score, so such a candidate takes nothing, and the recorded candidates keep their recorded scores); one line
     * each. Recording them is a device run, which is not planned: they are judged on the device, by use.
     */
    val unrecorded = ArrayList<String>()

    /** The legacy view of the recording's `score:type` batch, when it has one and no real `score:family` batch. */
    private val legacy: LegacyFamilyBridge.View? =
        if (recording.asks.any { it.name == "score:family" }) null else LegacyFamilyBridge.viewOf(recording)

    override suspend fun open(prefix: String): PamResult<Int> = PamResult.Success(recording.cost.prefixTokens ?: 0)

    override suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String> {
        // The live question text is followed by the chat template's closing of the turn; the recorded one is not.
        // Zone questions list their candidates with ids in the text: compared without them, so ids that moved still match.
        val text = withoutIds(question.removePrefix("\n\n"))
        val at = recording.asks.indices.firstOrNull { it !in used && !recording.asks[it].name.startsWith("score:") && text.startsWith(withoutIds(recording.asks[it].question)) }
            ?: return PamResult.Error(PamError.InferenceError("no recorded answer for this question")).also { misses += "ask «${text.take(MISS_CHARS)}»" }
        used += at
        val answer = recording.asks[at].answer ?: return PamResult.Error(PamError.InferenceError("the recorded question failed"))
        return PamResult.Success(IdRemap.inAnswer(answer, remap).let { if (recording.asks[at].name == QuestionNames.TYPE) legacyTypeAnswer(it) else it })
    }

    /** A generated type answer ("bill de HIGH") of a recording made before the families: its type id becomes the family's. */
    private fun legacyTypeAnswer(answer: String): String {
        val id = answer.trim().substringBefore(' ').trim('"')
        val family = LegacyTypes.of(id)?.family ?: return answer
        return answer.replaceFirst(id, family)
    }

    /** A recorded scored batch (`score:*`): its questions in order, its answer the comma-separated scores. */
    override suspend fun score(continuations: List<String>, yes: String, no: String, shared: String): PamResult<List<Double>> {
        // The stored-slot questions (key information) and the action questions are newer than the recordings of the reading: they are scripted
        // as "not scored" (no threshold accepts that), and the rest of the batch is replayed as recorded. The action scores are replayed
        // from their own recordings (ActionKindReplayTest).
        val keySlot = continuations.indices.filter { continuations[it].contains(KEY_SLOT_QUESTION) || ActionQuestions.isActionQuestion(continuations[it]) }.toSet()
        if (keySlot.isNotEmpty()) {
            val rest = continuations.filterIndexed { i, _ -> i !in keySlot }
            val replayed = if (rest.isEmpty()) PamResult.Success(emptyList()) else score(rest, yes, no, shared)
            if (replayed !is PamResult.Success) return replayed
            val scores = replayed.data.iterator()
            return PamResult.Success(continuations.indices.map { if (it in keySlot) LegacyFamilyBridge.NOT_RECORDED else scores.next() })
        }
        // A recording holds each question whole; the live one arrives as the shared level and the rest, read as one text.
        val asked = continuations.map { withoutIds((shared + it).removePrefix("\n\n")) }
        val live = asked
        // The batch as recorded; failing that, a subset of a recorded one in the same order (fewer candidates than were recorded).
        var picked: List<Int> = emptyList()
        // [newFamilies]: a family question the recording never held (a family added after it was made) is scripted as "not scored"
        // (-1 below), so the recorded families keep their recorded scores and the new ones can never win.
        fun find(subset: Boolean, newFamilies: Boolean = false): Int? = recording.asks.indices.firstOrNull { i ->
            val a = recording.asks[i]
            if (i in used || !a.name.startsWith("score:")) return@firstOrNull false
            val questions = a.question.split(SCORE_SEPARATOR).map { withoutIds(it) }
            if (!subset && questions.size != live.size) return@firstOrNull false
            val found = ArrayList<Int>()
            var from = 0
            for (l in live) {
                val j = (from until questions.size).firstOrNull { l.startsWith(questions[it]) }
                if (j == null) {
                    if (newFamilies && l.startsWith(FAMILY_QUESTION)) found += NEW_QUESTION else return@firstOrNull false
                    continue
                }
                found += j
                from = j + 1
            }
            if (found.all { it == NEW_QUESTION }) return@firstOrNull false
            picked = found
            true
        }
        val at = find(subset = false) ?: find(subset = true) ?: find(subset = true, newFamilies = true)
        if (at == null) {
            // The content-free baseline of a party question (a made-up name) is newer than the 16 recordings: replayed as recorded when the
            // recording holds it, else "not scored" (far below any score, so it never makes a name lose and the reading is as recorded).
            // The same holds for the baselines of the reference questions and for the meaning of a date or an amount (extraction-v2-14): never
            // recorded, so "not scored" and no meaning is decided in a replay.
            if (live.all { ScoringDescriptions.isBaselineQuestion(it) || isMeaningQuestion(it) }) return PamResult.Success(live.map { LegacyFamilyBridge.NOT_RECORDED })
            // The reference slots every family asks since extraction-v2-5 are newer than the recordings: a batch that holds them besides
            // recorded questions replays the recorded ones and scripts the new ones as "not scored".
            scriptedAroundNewCore(live)?.let { return it }
            // A party or slot question over candidates the layout used to leave out: never recorded, never read as a quiet zero.
            if (live.all { q -> LAYOUT_ORDERED_STATEMENTS.any { q.contains(it) } }) {
                unrecorded += "score «${live.firstOrNull().orEmpty().take(MISS_CHARS)}» x${live.size}"
                return PamResult.Success(live.map { LegacyFamilyBridge.NOT_RECORDED })
            }
            // The family and the topics of a recording made before the families: scripted from its legacy type scores, nothing else is.
            legacy?.let { view -> LegacyFamilyBridge.answer(view, live)?.let { return PamResult.Success(it) } }
            misses += "score «${live.firstOrNull().orEmpty().take(MISS_CHARS)}» x${live.size}"            // A question the old recording never held (a slot the old type did not have, an address line label) is scripted as "not scored":
            // every candidate gets a score no threshold accepts, so it takes nothing, and the question is listed in [misses]. An engine error
            // would instead count as three failures and abort the whole reading, which is not what the recording says.
            if (legacy != null) return PamResult.Success(live.map { LegacyFamilyBridge.NOT_RECORDED })
            return PamResult.Error(PamError.InferenceError("no recorded scores for this batch"))
        }
        used += at
        val answer = recording.asks[at].answer ?: return PamResult.Error(PamError.InferenceError("the recorded batch failed"))
        val scores = answer.split(',').map { it.trim().toDouble() }
        return PamResult.Success(picked.map { if (it == NEW_QUESTION) LegacyFamilyBridge.NOT_RECORDED else scores[it] })
    }

    /**
     * [live] is a recorded batch plus questions about the slots that became family-independent in extraction-v2-5 (never recorded for the
     * families that did not have them): the recorded questions get their recorded scores, the new ones [LegacyFamilyBridge.NOT_RECORDED]
     * (no threshold accepts that, so they take nothing). A batch of only such questions is scripted whole. Null when [live] holds any other
     * question the recording lacks: that one is a real miss.
     */
    private fun scriptedAroundNewCore(live: List<String>): PamResult<List<Double>>? {
        // An extras question about a value that no slot takes any more (a fee that waits for the model to lean Yes frees an amount) is new too.
        val scripted = live.map { q -> NEW_CORE_STATEMENTS.any { q.contains(it) } || q.contains(ScoringDescriptions.EXTRA) }
        for (i in recording.asks.indices) {
            val a = recording.asks[i]
            if (i in used || !a.name.startsWith("score:")) continue
            val recorded = a.question.split(SCORE_SEPARATOR).map { withoutIds(it) }
            val liveIndexOf = ArrayList<Int>()
            var from = 0
            for (q in recorded) {
                val j = (from until live.size).firstOrNull { live[it].startsWith(q) } ?: break
                liveIndexOf += j
                from = j + 1
            }
            if (liveIndexOf.size != recorded.size) continue
            // Every recorded question is there, as recorded. A live question the recording lacks is new input (a candidate the reading now
            // offers: a table row that is no longer a subject, an amount no slot takes any more): scripted as "not scored", never given a score.
            val answer = a.answer ?: return null
            val scores = answer.split(',').map { it.trim().toDouble() }
            used += i
            val byLive = liveIndexOf.withIndex().associate { (k, j) -> j to scores[k] }
            return PamResult.Success(live.indices.map { byLive[it] ?: LegacyFamilyBridge.NOT_RECORDED })
        }
        return if (scripted.all { it }) PamResult.Success(live.map { LegacyFamilyBridge.NOT_RECORDED }) else null
    }

    /** A grid is what several recorded batches hold: one per ask (each question's candidates), read the way the batches were recorded. */
    override suspend fun scoreGrid(shared: String, heads: List<String>, asks: List<String>, yes: String, no: String): PamResult<List<List<Double>>> {
        val columns = ArrayList<List<Double>>()
        for (ask in asks) {
            when (val column = score(heads.map { it + ask }, yes, no, shared)) {
                is PamResult.Error -> return column
                is PamResult.Success -> columns += column.data
            }
        }
        return PamResult.Success(heads.indices.map { i -> asks.indices.map { j -> columns[j][i] } })
    }

    /** A question about what a date or an amount means: it carries one of the registry's descriptions as its statement. */
    private fun isMeaningQuestion(question: String): Boolean = ValueMeanings.DEFAULT.all.any { question.contains(" ${it.description}? Answer:") }

    private fun withoutIds(text: String) =ID_TOKEN.replace(withoutContext(withoutHint(text)), "#")

    /**
     * A value's question is "Is «value» [printed after «label»] (context: its row and the rows around it) <statement>? Answer:". What stands
     * between the value and the statement is the input the model is shown with it (the row a value is printed in, the label before a number),
     * and it changed after the recordings were made (rows instead of lines, the physical order of the page, the printed label). The recorded
     * score is the model's score of that value under that statement; it is replayed for the same value and statement whatever the context
     * text was, as the family hint is (what the new context does to the score needs a recording made on the device). A question the
     * recording does not hold at all (another value, another statement) is still a miss.
     */
    private fun withoutContext(text: String): String {
        // The naming of an extra carries its row the same way: "The value «X» is an important fact of this letter: it is printed on the line «...». What does ..."
        val fact = text.indexOf(NAMING_FACT)
        val naming = text.indexOf(NAMING_ASK)
        if (text.startsWith("QUESTION: The value «") && fact >= 0 && naming > fact) return text.substring(0, fact + NAMING_FACT.length) + text.substring(naming)
        val head = text.indexOf("Is «").takeIf { it >= 0 } ?: return text
        val valueEnd = text.indexOf('»', head).takeIf { it >= 0 } ?: return text
        val statement = STATEMENTS.map { text.indexOf(" $it? Answer:", valueEnd) }.filter { it >= 0 }.minOrNull() ?: return text
        return text.substring(0, valueEnd + 1) + text.substring(statement)
    }

    /**
     * The extras are scored under the family's hint now ([ScoringDescriptions.extra]); a recording made before holds the plain statement.
     * Compared without the hint, so the recorded scores still stand for every extra (what the hint does to the scores is not in them:
     * it needs a recording made on the device).
     */
    private fun withoutHint(text: String): String = HINTED.fold(text) { t, (hinted, plain) -> t.replace(hinted, plain) }

    private companion object {
        val ID_TOKEN = Regex("\\b[A-Z]{1,2}\\d{1,3}\\b")
        val HINTED: List<Pair<String, String>> = ExtractionSchema.DEFAULT.families.map { it.hint }.filter { it.isNotBlank() }
            .map { "${ScoringDescriptions.EXTRA}. $it" to ScoringDescriptions.EXTRA }
        const val KEY_SLOT_QUESTION = "the reader need «"
        const val NAMING_FACT = " is an important fact of this letter"
        const val NAMING_ASK = ". What does the letter call this value?"

        /** Every statement a value's scoring question can close with (see [withoutContext]). */
        val STATEMENTS: List<String> =
            (ExtractionSchema.DEFAULT.allSlots + Slots.CORE).distinct().map { ScoringDescriptions.ofSlot(it) } +
                listOf(QuestionNames.SENDER, QuestionNames.ADDRESSEE, QuestionNames.CARE_OF, QuestionNames.CONTACT, QuestionNames.SUBJECT_PERSON)
                    .map { ScoringDescriptions.ofRole(it) } +
                ScoringDescriptions.KINDS.map { it.second } + ScoringDescriptions.HOUSEHOLD + ScoringDescriptions.EXTRA

        /** The statements of the party and slot questions: the ones whose candidates the layout orders (see [unrecorded]). */
        val LAYOUT_ORDERED_STATEMENTS: List<String> =
            (ExtractionSchema.DEFAULT.allSlots + Slots.CORE).distinct().map { ScoringDescriptions.ofSlot(it) } +
                listOf(QuestionNames.SENDER, QuestionNames.ADDRESSEE, QuestionNames.CARE_OF, QuestionNames.CONTACT, QuestionNames.SUBJECT_PERSON)
                    .map { ScoringDescriptions.ofRole(it) }

        /** The statements of the reference slots that every family asks since extraction-v2-5 (see [scriptedAroundNewCore]). */
        val NEW_CORE_STATEMENTS: List<String> = listOf(Slots.INVOICE_NO, Slots.CONTRACT_NO, Slots.POLICY_NO, Slots.CASE_NO, Slots.TAX_NO)
            .map { ScoringDescriptions.ofSlot(it) }
        const val SCORE_SEPARATOR = "\n@@\n"
        const val MISS_CHARS = 70

        /** How a family question starts, and the marker of one the recording never held (see `find`). */
        const val FAMILY_QUESTION = "Is this document "
        const val NEW_QUESTION = -1
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

    /** The session of the reading that replays (not the throwaway ones that answer the prompt overheads), for [misses]. */
    private var reading: ReplayPromptSession? = null

    /** What the replay asked that the recording has no answer for (see [ReplayPromptSession.misses]). */
    val misses: List<String> get() = reading?.misses.orEmpty()

    /**
     * Fails clearly when a recording made for the current interpreter (one with a real `score:family` batch) lacks an answer to something the
     * interpreter now asks: a question that changed since the device run has to be recorded again, never read as a silent zero. A recording
     * made before the families lacks the questions that did not exist then; it is exempt, and [misses] lists them.
     *
     * The one question that is SCRIPTED instead ([scriptedMisses]) is the summary's: its text is the facts the reading decided, so a replay
     * under a profile that decides one fact differently from the recorded run asks a question the recording cannot hold. It is answered as the
     * template summary (what the writer gives when it gets no answer), and listed; nothing a metric reads depends on it.
     *
     * The key-information ask (`KeyInfoWriter`, extraction-v2-15) is scripted the same way: no recording holds that generation (recording it
     * is a device run, which is not planned), so it is answered as "no more facts" and the replay's extras are none. It replaces the scored
     * extras, which the recordings do hold and which the replay no longer asks.
     */
    fun requireComplete() {
        val hard = misses.filterNot { it in scriptedMisses }
        if (recording.asks.none { it.name == "score:family" } || hard.isEmpty()) return
        error("the recording ${recording.key}.${recording.variant} has no answer for: ${hard.joinToString("; ")}; record it again on the device")
    }

    /** The summary asks and the key-information ask the recording could not answer (see [requireComplete]): the template summary, and no extra facts. */
    val scriptedMisses: List<String> get() = misses.filter { it.startsWith("ask «FACTS") || it.startsWith("ask «READ FIELDS") }

    /** The party and slot questions the recording never held, replayed as "not recorded" (see [ReplayPromptSession.unrecorded]). */
    val unrecorded: List<String> get() = reading?.unrecorded.orEmpty()

    private fun create(offered: OfferedCandidates?): DocumentInterpreter {
        val remap = offered?.let { IdRemap.between(recording.candidates, it) } ?: emptyMap()
        val session = ReplayPromptSession(recording, remap)
        if (offered != null) reading = session
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

    // The scoring interpreter is staged: the pipeline asks it (once it has interpreted) for the second stage.
    override val staged: Boolean get() = inner?.staged ?: false

    override suspend fun enrich(request: com.postsaimanager.core.domain.extraction.v2.EnrichmentRequest): com.postsaimanager.core.domain.extraction.v2.EnrichmentOutcome =
        (inner ?: create(request.offered).also { inner = it }).enrich(request)

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

    /**
     * Letters whose current-interpreter recording (the scoring variant) decided the family `email_printout`, which is no family any more
     * (it misread two postal letters). Replayed now, such a letter is read as another family and asks slot questions the device run never
     * recorded, so it cannot replay in full. Only a new device recording of the letter closes this; until then it is left out of the
     * replays that require a full one.
     */
    val PENDING_RERECORD: Set<String> = setOf("tax-long-7p")

    /** The recordings in [dir] that can be replayed in full: all but those of [PENDING_RERECORD]. */
    fun loadReplayable(dir: File): List<Recording> =
        Recordings.load(dir).filterNot { it.key in PENDING_RERECORD && it.variant == SCORING_VARIANT + "3" }

    /** Scores every variant found in [dir]; empty when there are no recordings (never an error). */
    fun scoreAll(docs: List<Pair<ManifestDoc, Fixture>>, dir: File): List<InterpreterScore> =
        // The scoring variants are decided with the shipped profile: a recording of the current interpreter holds the questions that profile's
        // decisions ask (the facts of the summary, the address lines the parties settle), and the default profile would decide others.
        loadReplayable(dir).groupBy { it.variant }.mapNotNull { (variant, recs) ->
            score(variant, docs, recs, com.postsaimanager.core.domain.extraction.zones.ModelProfiles.QWEN35_08B.scoring)
        }

    /** One recording replayed through the real interpreter of its variant, the real pipeline and the verifier. */
    fun replayResult(rec: Recording, f: Fixture, scoring: ScoringProfile = ScoringProfile()): ExtractionV2Result {
        val replay: DocumentInterpreter = when {
            rec.variant.startsWith(SCORING_VARIANT) -> ZoneReplay(rec, scoring)
            rec.variant.startsWith(ZONES_VARIANT) -> ZoneReplay(rec, null)
            rec.isQuestionnaire -> QuestionnaireReplay(rec)
            else -> ScriptedInterpreter(rec)
        }
        val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
        val result = runBlocking {
            ExtractionV2Pipeline().run(f.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height })
        }
        // A scoring replay whose reading failed is never read as an empty result: a question the recording cannot answer is said so.
        if (replay is ZoneReplay && rec.variant.startsWith(SCORING_VARIANT)) {
            check(result.diagnostics.modelUsed) { "the replay of ${rec.key}.${rec.variant} failed: ${result.diagnostics.modelError}; misses: ${replay.misses}" }
            replay.requireComplete()
        }
        return result
    }

    /** What a scoring replay asked that its recording could not answer: the questions to record again (or, for a legacy recording, the ones that never existed). */
    fun replayMisses(rec: Recording, f: Fixture, scoring: ScoringProfile): Misses {
        val replay = ZoneReplay(rec, scoring)
        val first = f.pages.firstOrNull()?.takeIf { it.height > 0 }
        runBlocking { ExtractionV2Pipeline().run(f.pages.map { it.blocks }, replay, rec.contextTokens, first?.let { it.width.toFloat() / it.height }) }
        return Misses(replay.misses.filterNot { it in replay.scriptedMisses }, replay.scriptedMisses, replay.unrecorded)
    }

    /** [hard]: questions to record again; [scripted]: the summary asks replayed as the template (see [ZoneReplay.requireComplete]). */
    class Misses(val hard: List<String>, val scripted: List<String>, val unrecorded: List<String> = emptyList())

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
