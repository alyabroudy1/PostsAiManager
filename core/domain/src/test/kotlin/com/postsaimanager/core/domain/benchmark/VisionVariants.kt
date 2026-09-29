package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Scores the phone recordings of the vision spike (workstream H) with one set of definitions for
 * every variant, so text-only (`t`), text plus page-1 image (`ti`) and image-only (`i`) compare
 * directly. `t`/`ti` recordings are the standard `{call1, contextTokens}` files replayed through
 * the real v2 pipeline; `i` recordings hold one free-value answer per page (no candidate ids,
 * so its values are unverifiable) and are merged here: a slot takes its most confident value
 * across pages, ties to the earliest page.
 */
class VariantOutcome(
    val key: String,
    val variant: String,
    val parsed: Boolean,
    /** Normalised values the variant produced, with the model's confidence word as a number. */
    val values: List<Value>,
    val sender: String?,
    val addressees: List<String>,
    val extras: Int,
    /** Dropped answers (ids outside the offered set, quotes not in the letter); always 0 for `i`. */
    val rejected: Int,
    val call1Ms: Long,
    val vision: String,
) {
    class Value(val slot: String, val raw: String, val normalized: String, val confidence: Double, val grounded: Boolean)
}

class VisionReplay(
    private val dir: File,
    private val docs: List<Pair<ManifestDoc, Fixture>>,
) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val byKey = docs.associate { it.first.key to it }

    fun outcome(key: String, variant: String): VariantOutcome? {
        val f = File(dir, "$key.$variant.json").takeIf { it.exists() } ?: return null
        val o = json.parseToJsonElement(f.readText()).jsonObject
        val ms = o.str("call1Ms")?.toLongOrNull() ?: 0L
        val (m, fixture) = byKey.getValue(key)
        val pages = fixture.pages.map { it.blocks }
        return if (variant.startsWith("i")) images(o, m, pages, key, variant, ms) else call1(f, m, pages, key, variant, ms, o)
    }

    private fun call1(
        f: File, m: ManifestDoc, pages: List<List<com.postsaimanager.core.model.OcrBlock>>, key: String, variant: String, ms: Long, o: JsonObject,
    ): VariantOutcome {
        val rec = Recordings.load(f.parentFile).firstOrNull { it.key == key && it.variant == variant }
            ?: return VariantOutcome(key, variant, false, emptyList(), null, emptyList(), 0, 0, ms, o.str("vision").orEmpty())
        val result = runBlocking { ExtractionV2Pipeline().run(pages, ScriptedInterpreter(rec), rec.contextTokens) }
        val text = ExtractionBenchmark.squash(pages.joinToString("\n") { p -> p.joinToString("\n") { it.text } })
        val values = (result.slots.values + result.slotLists.values.flatten() + result.extras.map { it.value })
            .filter { it.slot?.kind != SlotKind.ACTION }
            .map {
                VariantOutcome.Value(
                    it.slot?.json ?: "x", it.value, it.normalized, it.aiConfidence.toDouble(),
                    it.candidateId != null || text.contains(ExtractionBenchmark.squash(it.value)),
                )
            }
        return VariantOutcome(
            key, variant, result.diagnostics.modelUsed, values,
            result.parties.sender?.name,
            result.parties.all.filter { it.role == PartyRole.ADDRESSEE || it.role == PartyRole.CO_ADDRESSEE }.map { it.name },
            result.extras.size, result.diagnostics.rejections.size, ms, o.str("vision").orEmpty(),
        )
    }

    private fun images(
        o: JsonObject, m: ManifestDoc, pages: List<List<com.postsaimanager.core.model.OcrBlock>>, key: String, variant: String, ms: Long,
    ): VariantOutcome {
        val text = ExtractionBenchmark.squash(pages.joinToString("\n") { p -> p.joinToString("\n") { it.text } })
        val answers = (o["pageAnswers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val objs = answers.map { runCatching { objectOf(it["raw"]) }.getOrNull() }
        val best = LinkedHashMap<String, VariantOutcome.Value>()
        val parties = mutableListOf<Pair<String, String>>()
        var extras = 0
        for (obj in objs) {
            obj ?: continue
            (obj["parties"] as? JsonArray)?.forEach { p ->
                val po = p as? JsonObject ?: return@forEach
                val role = po.str("r") ?: return@forEach
                val name = po.str("name") ?: return@forEach
                if (parties.none { it.first == role && QuoteVerifier.fold(it.second) == QuoteVerifier.fold(name) }) parties += role to name
            }
            (obj["s"] as? JsonObject)?.forEach { (slot, v) ->
                val vo = v as? JsonObject ?: return@forEach
                val raw = vo.str("v")?.trim().orEmpty()
                if (raw.isEmpty() || raw == "NONE") return@forEach
                val c = when (vo.str("c")) { "HIGH" -> 0.9; "MEDIUM" -> 0.7; else -> 0.4 }
                val norm = normalise(slot, raw)
                val have = best[slot]
                if (have == null || c > have.confidence) {
                    best[slot] = VariantOutcome.Value(slot, raw, norm, c, text.contains(ExtractionBenchmark.squash(raw)))
                }
            }
            extras += (obj["x"] as? JsonArray)?.count { (it as? JsonObject)?.str("v").let { v -> !v.isNullOrBlank() } } ?: 0
        }
        val okPages = objs.count { it != null }
        return VariantOutcome(
            key, variant, okPages == answers.size && okPages > 0, best.values.toList(),
            parties.firstOrNull { it.first == PartyRole.SENDER.name }?.second,
            parties.filter { it.first == PartyRole.ADDRESSEE.name || it.first == PartyRole.CO_ADDRESSEE.name }.map { it.second },
            extras, 0, ms, answers.firstOrNull()?.str("vision").orEmpty(),
        )
    }

    private fun objectOf(el: JsonElement?): JsonObject? {
        val text = (el as? JsonPrimitive)?.contentOrNull ?: return null
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return json.parseToJsonElement(text.substring(s, e + 1)).jsonObject
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Free text brought to the form expectations use (ISO date, "1284.50 EUR", compact IBAN). */
    private fun normalise(slot: String, raw: String): String {
        val kinds = SLOT_KIND[slot] ?: return raw.filter { !it.isWhitespace() }
        val hit = CandidateExtractor.extractFromText(raw).candidates.firstOrNull { it.kind in kinds }
        return hit?.normalized ?: raw.filter { !it.isWhitespace() }
    }

    companion object {
        private val DATES = setOf(CandidateKind.DATE, CandidateKind.DATETIME)
        private val SLOT_KIND: Map<String, Set<CandidateKind>> = mapOf(
            "total" to setOf(CandidateKind.AMOUNT), "fee" to setOf(CandidateKind.AMOUNT),
            "new_amount" to setOf(CandidateKind.AMOUNT), "previous_amount" to setOf(CandidateKind.AMOUNT),
            "proof_amount" to setOf(CandidateKind.AMOUNT),
            "letter_date" to DATES, "due_date" to DATES, "objection_deadline" to DATES, "original_due_date" to DATES,
            "effective_date" to DATES, "contract_end" to DATES, "event_date" to DATES, "appointment" to DATES,
            "sent_date" to DATES, "proof_date" to DATES,
            "iban" to setOf(CandidateKind.IBAN),
        )
    }
}
