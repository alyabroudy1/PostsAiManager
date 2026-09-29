package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.InterpretationParser
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Locale
import com.postsaimanager.core.domain.extraction.v2.ScriptedInterpreter as RecordedCall1

/**
 * Replays the raw answers recorded on the phone by `VisionBenchmarkTest` (core:ai:local
 * androidTest) as a [BenchmarkInterpreter], so the shared [InterpreterMetrics] score them.
 *
 * Variants (file `recordings/<key>.<variant>.json`):
 *  - `t`  text only. The raw call-1 answer runs through D's pipeline (verifier) with the scripted model.
 *  - `ti` text plus page 1 as an image. Same replay.
 *  - `i`  image only, one free-value answer per page. Values are **unverifiable** (no candidate
 *    ids, no bounding boxes): they are normalised like OCR candidates and merged across pages,
 *    a slot taking its most confident value (ties: the earliest page).
 *
 * Manifest fields are mapped onto slots leniently and identically for every variant: a manifest
 * field counts as answered correctly when any of its slots holds the expected value.
 */
class RecordedVariant(
    private val dir: File,
    private val variant: String,
    private val fixtures: Map<String, Fixture>,
    private val manifests: Map<String, ManifestDoc>,
) : BenchmarkInterpreter {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Everything a recording says about one document after replay. */
    class Replay(val doc: InterpretedDocument, val call1Ms: Long, val parsed: Boolean, val rawExtras: Int)

    var lastReplays = mutableMapOf<String, Replay>()

    override fun interpret(input: InterpreterInput): InterpretedDocument? = replay(input.key)?.doc

    fun replay(key: String): Replay? {
        val f = File(dir, "$key.$variant.json").takeIf { it.exists() } ?: return null
        val o = json.parseToJsonElement(f.readText()).jsonObject
        val ms = (o["call1Ms"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val pages = fixtures.getValue(key).pages.map { it.blocks }
        val expected = manifests.getValue(key)
        val r = if (variant == "i") replayImages(o, expected, ms) else replayCall1(o, pages, expected, ms)
        lastReplays[key] = r
        return r
    }

    private fun replayCall1(o: JsonObject, pages: List<List<com.postsaimanager.core.model.OcrBlock>>, m: ManifestDoc, ms: Long): Replay {
        val raw = (o["raw"] as? JsonPrimitive)?.contentOrNull
        val parsed = raw?.let { InterpretationParser.parse(it) } as? InterpretationParser.Parsed.Ok
        if (raw == null || parsed == null) {
            return Replay(InterpretedDocument(emptyMap(), null, emptyMap(), emptyList()), ms, false, 0)
        }
        val result = runBlocking { ExtractionV2Pipeline().run(pages, RecordedCall1(raw, null), 4096) }
        val slots = result.slots.mapKeys { it.key.json }
        val values = slots.mapValues { it.value.normalized }
        val conf = slots.mapValues { it.value.aiConfidence.toDouble() }
        val roles = Roles(
            result.parties.sender?.name,
            result.parties.allAddressees.map { it.name },
        )
        return Replay(
            InterpretedDocument(fieldsFor(m, values), roles, confFor(m, conf), result.extras.map { it.label }),
            ms, true, parsed.value.extras.size,
        )
    }

    private fun replayImages(o: JsonObject, m: ManifestDoc, ms: Long): Replay {
        val answers = (o["pageAnswers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
        val perPage = answers.map { a -> a to runCatching { extractObject(a["raw"]) }.getOrNull() }
        val ok = perPage.count { it.second != null }
        val slotValue = LinkedHashMap<String, Pair<String, Double>>() // slot -> (normalised value, confidence)
        val parties = mutableListOf<Triple<String, String, Double>>() // role, name
        val extras = mutableListOf<String>()
        for ((_, obj) in perPage) {
            obj ?: continue
            (obj["parties"] as? JsonArray)?.forEach { p ->
                val po = p as? JsonObject ?: return@forEach
                val role = po.str("r") ?: return@forEach
                val name = po.str("name") ?: return@forEach
                if (parties.none { it.first == role && QuoteVerifier.fold(it.second) == QuoteVerifier.fold(name) }) {
                    parties += Triple(role, name, wordConf(po.str("c")))
                }
            }
            (obj["s"] as? JsonObject)?.forEach { (slot, v) ->
                val vo = v as? JsonObject ?: return@forEach
                val text = vo.str("v") ?: return@forEach
                val norm = normalise(slot, text)
                val c = wordConf(vo.str("c"))
                val have = slotValue[slot]
                if (have == null || c > have.second) slotValue[slot] = norm to c
            }
            (obj["x"] as? JsonArray)?.forEach { x ->
                (x as? JsonObject)?.str("lb")?.let { if (it !in extras) extras += it }
            }
        }
        val roles = Roles(
            parties.firstOrNull { it.first == PartyRole.SENDER.name }?.second,
            parties.filter { it.first == PartyRole.ADDRESSEE.name || it.first == PartyRole.CO_ADDRESSEE.name }.map { it.second },
        )
        val values = slotValue.mapValues { it.value.first }
        val conf = slotValue.mapValues { it.value.second }
        return Replay(
            InterpretedDocument(fieldsFor(m, values), roles, confFor(m, conf), extras),
            ms, ok == answers.size && ok > 0, extras.size,
        )
    }

    private fun extractObject(el: kotlinx.serialization.json.JsonElement?): JsonObject? {
        val text = (el as? JsonPrimitive)?.contentOrNull ?: return null
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return json.parseToJsonElement(text.substring(s, e + 1)).jsonObject
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun wordConf(w: String?) = when (w) { "HIGH" -> 0.9; "MEDIUM" -> 0.7; "LOW" -> 0.4; else -> 0.0 }

    /** Free text from an image answer, brought to the form manifest expectations use (ISO date, "1284.50 EUR", ...). */
    private fun normalise(slot: String, text: String): String {
        val kind = SLOT_KIND[slot]
        if (kind == null) return text.trim()
        val set = CandidateExtractor.extractFromText(text)
        val hit = set.candidates.firstOrNull { it.kind in kind }
        return hit?.normalized ?: text.trim().filter { !it.isWhitespace() }
    }

    private fun fieldsFor(m: ManifestDoc, values: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (e in m.expected) {
            val name = e.field.substringBefore(' ')
            val slots = SLOTS_OF[name] ?: SLOTS_OF.entries.firstOrNull { name.startsWith(it.key) }?.value ?: continue
            val present = slots.mapNotNull { values[it] }
            if (present.isEmpty()) continue
            // Any of the mapped slots holding the expected value counts (lenient, the same for every variant).
            val want = Expectations.of(m).firstOrNull { it.field == e.field }?.norm
            out[name] = present.firstOrNull { want != null && ExtractionBenchmark.squash(it) == ExtractionBenchmark.squash(want) }
                ?: present.first()
        }
        return out
    }

    private fun confFor(m: ManifestDoc, conf: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (e in m.expected) {
            val name = e.field.substringBefore(' ')
            val slots = SLOTS_OF[name] ?: SLOTS_OF.entries.firstOrNull { name.startsWith(it.key) }?.value ?: continue
            slots.firstNotNullOfOrNull { conf[it] }?.let { out[name] = it }
        }
        return out
    }

    companion object {
        private val DATE = setOf(CandidateKind.DATE, CandidateKind.DATETIME, CandidateKind.RELATIVE_DEADLINE)
        private val SLOT_KIND: Map<String, Set<CandidateKind>> = mapOf(
            "total" to setOf(CandidateKind.AMOUNT), "fee" to setOf(CandidateKind.AMOUNT),
            "new_amount" to setOf(CandidateKind.AMOUNT), "previous_amount" to setOf(CandidateKind.AMOUNT),
            "proof_amount" to setOf(CandidateKind.AMOUNT),
            "letter_date" to DATE, "due_date" to DATE, "objection_deadline" to DATE, "original_due_date" to DATE,
            "effective_date" to DATE, "contract_end" to DATE, "event_date" to DATE, "appointment" to DATE,
            "sent_date" to DATE, "proof_date" to DATE,
            "iban" to setOf(CandidateKind.IBAN),
        )

        /** Manifest field name (up to the first space) to the slots that can hold it. */
        val SLOTS_OF: Map<String, List<String>> = linkedMapOf(
            "amount" to listOf("total", "new_amount", "proof_amount"),
            "fee" to listOf("fee"),
            "previous_amount" to listOf("previous_amount"),
            "old_premium" to listOf("previous_amount"),
            "new_premium" to listOf("new_amount"),
            "date" to listOf("letter_date", "sent_date"),
            "letter_date" to listOf("letter_date", "sent_date"),
            "invoice_date" to listOf("letter_date"),
            "deadline" to listOf("due_date", "objection_deadline"),
            "due_date" to listOf("due_date"),
            "original_due_date" to listOf("original_due_date"),
            "iban" to listOf("iban"),
            "reference_number" to listOf("invoice_no", "reference", "case_no", "receipt_no", "contract_no"),
            "customer_number" to listOf("customer_no"),
            "tax_number" to listOf("tax_no"),
            "policy_number" to listOf("policy_no"),
            "contract_end" to listOf("contract_end"),
            "appointment" to listOf("appointment"),
        )

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    }
}
