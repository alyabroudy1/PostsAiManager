package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.usecase.LetterLayout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/*
 * EXTENSION POINT FOR WORKSTREAM D (DocumentInterpreter / ExtractionV2Result).
 *
 * D's types are not on this branch, so the model stage is scored through these neutral stand-ins.
 * To wire the real thing:
 *   1. Implement [BenchmarkInterpreter] with an adapter that calls D's `DocumentInterpreter` with the
 *      layout description and the candidate set, and maps its `ExtractionV2Result` to [InterpretedDocument]
 *      (field name -> normalised value, roles, per-field confidence, and every extra field it returned).
 *   2. Offline / CI: record the raw model answers once on device into
 *      `src/test/resources/benchmark/recordings/<key>.json` and let [ScriptedInterpreter] replay them
 *      (the adapter's parser must then run on the recorded raw text; replace [ScriptedInterpreter.interpret]
 *      accordingly). Recording format below.
 *   3. Score with [InterpreterMetrics.score]; add its numbers to the scoreboard and, once stable, to baseline.json.
 *
 * Recording format (`recordings/<key>.json`):
 *   { "fields": {"amount": "1284.50 EUR", "deadline": "2026-10-15", "sender": "Musterfirma GmbH"},
 *     "roles": {"sender": "...", "addressees": ["..."]},
 *     "confidence": {"amount": 0.93, "deadline": 0.4},
 *     "extras": ["anything else the model produced"] }
 *
 * Field names follow the manifest's `expected[].field` up to the first space (amount, deadline, iban, ...).
 */

class InterpreterInput(val key: String, val layout: LetterLayout, val candidates: CandidateSet)

class InterpretedDocument(
    val fields: Map<String, String?>,
    val roles: Roles?,
    val confidence: Map<String, Double>,
    val extras: List<String>,
)

fun interface BenchmarkInterpreter {
    fun interpret(input: InterpreterInput): InterpretedDocument?
}

/** Replays recorded answers; returns null when a document has no recording. */
class ScriptedInterpreter(private val dir: File) : BenchmarkInterpreter {
    private val json = Json { ignoreUnknownKeys = true }

    override fun interpret(input: InterpreterInput): InterpretedDocument? {
        val f = File(dir, "${input.key}.json").takeIf { it.exists() } ?: return null
        val o = json.parseToJsonElement(f.readText()).jsonObject
        val fields = (o["fields"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull }.orEmpty()
        val rolesObj = o["roles"] as? JsonObject
        val roles = rolesObj?.let { r ->
            Roles(
                r["sender"]?.jsonPrimitive?.contentOrNull,
                (r["addressees"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }
        val conf = (o["confidence"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content.toDouble() }.orEmpty()
        val extras = (o["extras"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return InterpretedDocument(fields, roles, conf, extras)
    }
}

class InterpreterScore(
    /** Expected facts whose normalised value the model returned, over expected facts it was asked for. */
    val fieldMatch: Double,
    /** Sender and addressees both right, over documents with known roles. */
    val rolesMatch: Double,
    /** Answers not grounded in the candidates or the OCR text, over all answers. */
    val hallucination: Double,
    /** Extra items per document. */
    val extrasPerDoc: Double,
    /** Confidence bucket ("0.0-0.5", "0.5-0.8", "0.8-1.0") -> accuracy of the answers in it. */
    val calibration: Map<String, Double>,
)

object InterpreterMetrics {

    fun score(
        docs: List<Pair<DocResult, ManifestDoc>>,
        interpreter: BenchmarkInterpreter,
    ): InterpreterScore? {
        var asked = 0
        var right = 0
        var answers = 0
        var ungrounded = 0
        var extras = 0
        var scored = 0
        var roleDocs = 0
        var roleRight = 0
        val buckets = linkedMapOf("0.0-0.5" to (0 to 0), "0.5-0.8" to (0 to 0), "0.8-1.0" to (0 to 0))
        for ((res, m) in docs) {
            val out = interpreter.interpret(InterpreterInput(res.key, res.layout, res.candidateSet)) ?: continue
            scored++
            val text = ExtractionBenchmark.squash(res.layout.plainText())
            val candidateNorms = res.candidateSet.candidates.map { ExtractionBenchmark.squash(it.normalized) }.toSet()
            for (f in res.facts) {
                val name = f.exp.field.substringBefore(' ')
                val answer = out.fields[name] ?: continue
                asked++
                val ok = ExtractionBenchmark.squash(answer) == ExtractionBenchmark.squash(f.exp.norm)
                if (ok) right++
                val c = out.confidence[name]
                if (c != null) {
                    val key = when { c < 0.5 -> "0.0-0.5"; c < 0.8 -> "0.5-0.8"; else -> "0.8-1.0" }
                    val (n, k) = buckets.getValue(key)
                    buckets[key] = (n + 1) to (k + if (ok) 1 else 0)
                }
            }
            for ((_, v) in out.fields) {
                if (v.isNullOrBlank()) continue
                answers++
                val sq = ExtractionBenchmark.squash(v)
                if (sq !in candidateNorms && !text.contains(sq)) ungrounded++
            }
            extras += out.extras.size
            if (m.roles.addressees.isNotEmpty() || m.senderName != null) {
                roleDocs++
                val r = out.roles
                val senderOk = m.senderName == null || (r?.sender?.let { ExtractionBenchmark.squash(it).contains(ExtractionBenchmark.squash(m.senderName!!.substringBefore(','))) } == true)
                val addrOk = m.roles.addressees.all { a -> r?.addressees.orEmpty().any { ExtractionBenchmark.squash(it).contains(ExtractionBenchmark.squash(a.substringBefore(','))) } }
                if (senderOk && addrOk) roleRight++
            }
        }
        if (scored == 0) return null
        fun r(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
        return InterpreterScore(
            fieldMatch = r(right, asked),
            rolesMatch = r(roleRight, roleDocs),
            hallucination = r(ungrounded, answers),
            extrasPerDoc = extras.toDouble() / scored,
            calibration = buckets.mapValues { (_, v) -> r(v.second, v.first) },
        )
    }

    fun format(s: InterpreterScore): String = String.format(
        Locale.ROOT,
        "fieldMatch=%.3f rolesMatch=%.3f hallucination=%.3f extrasPerDoc=%.2f calibration=%s",
        s.fieldMatch, s.rolesMatch, s.hallucination, s.extrasPerDoc, s.calibration,
    )
}
