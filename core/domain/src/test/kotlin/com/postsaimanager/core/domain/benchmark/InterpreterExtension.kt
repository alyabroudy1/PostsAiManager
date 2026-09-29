package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
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

/** One recorded model run: the two raw answers. */
class Recording(val key: String, val variant: String, val call1: String, val call2: String?, val contextTokens: Int)

object Recordings {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every `<key>.<variant>.json` in [dir], sorted; empty when the directory is missing. */
    fun load(dir: File): List<Recording> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val stem = f.name.removeSuffix(".json")
            val key = stem.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val o = json.parseToJsonElement(f.readText()).jsonObject
            val call1 = o["call1"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Recording(
                key, stem.substringAfterLast('.'), call1,
                o["call2"]?.jsonPrimitive?.contentOrNull,
                o["contextTokens"]?.jsonPrimitive?.intOrNull ?: 4096,
            )
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
        return when (val parsed = InterpretationParser.parse(recording.call1)) {
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value, recording.call1, "", grammar)
            is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, recording.call1.take(300), "", grammar)
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

class InterpreterScore(
    val variant: String,
    val docs: Int,
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
)

object InterpreterMetrics {

    private const val LOW = "0.0-0.5"
    private const val MID = "0.5-0.8"
    private const val HIGH = "0.8-1.0"

    /** Scores every variant found in [dir]; empty when there are no recordings (never an error). */
    fun scoreAll(docs: List<Pair<ManifestDoc, Fixture>>, dir: File): List<InterpreterScore> =
        Recordings.load(dir).groupBy { it.variant }.mapNotNull { (variant, recs) -> score(variant, docs, recs) }

    fun score(variant: String, docs: List<Pair<ManifestDoc, Fixture>>, recordings: List<Recording>): InterpreterScore? {
        val byKey = recordings.associateBy { it.key }
        var expectedFound = 0
        var matched = 0
        var given = 0
        var bad = 0
        var extras = 0
        var scored = 0
        var roleDocs = 0
        var roleRight = 0
        val buckets = linkedMapOf(LOW to (0 to 0), MID to (0 to 0), HIGH to (0 to 0))

        for ((m, f) in docs) {
            val rec = byKey[m.key] ?: continue
            val det = ExtractionBenchmark.score(m, f)
            val pages = f.pages.map { it.blocks }
            val result = runBlocking { ExtractionV2Pipeline().run(pages, ScriptedInterpreter(rec), rec.contextTokens) }
            scored++

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

            for (v in values.filter { it.slot?.kind != SlotKind.ACTION }) {
                val key = when { v.aiConfidence < 0.5f -> LOW; v.aiConfidence < 0.8f -> MID; else -> HIGH }
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
            fieldMatch = r(matched, expectedFound),
            rolesMatch = r(roleRight, roleDocs),
            hallucination = if (given == 0) 0.0 else bad.toDouble() / given,
            extrasPerDoc = extras.toDouble() / scored,
            calibration = buckets.mapValues { (_, v) -> r(v.second, v.first) to v.first },
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
        sb.appendLine("| variant | docs | field match | roles | hallucination | extras/doc | calibration (accuracy, n) |")
        sb.appendLine("|---|---|---|---|---|---|---|")
        for (s in scores) {
            sb.appendLine(
                "| ${s.variant} | ${s.docs} | ${pct(s.fieldMatch)} | ${pct(s.rolesMatch)} | ${pct(s.hallucination)} | " +
                    String.format(Locale.ROOT, "%.2f", s.extrasPerDoc) + " | " +
                    s.calibration.entries.joinToString(", ") { "${it.key}: ${pct(it.value.first)} (${it.value.second})" } + " |",
            )
        }
        return sb.toString()
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)
}
