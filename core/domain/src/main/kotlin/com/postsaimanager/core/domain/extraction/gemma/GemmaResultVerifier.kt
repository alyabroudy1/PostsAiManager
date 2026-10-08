package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.v2.Canonical
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner.Check
import com.postsaimanager.core.domain.extraction.v2.Diagnostics
import com.postsaimanager.core.domain.extraction.v2.ExtraValue
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.FreeText
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.QuoteMatch
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.domain.extraction.v2.RawExtra
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawParty
import com.postsaimanager.core.domain.extraction.v2.RawSlot
import com.postsaimanager.core.domain.extraction.v2.RawText
import com.postsaimanager.core.domain.extraction.v2.ResultVerifier
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.extraction.v2.SlotValue
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.domain.extraction.v2.VerificationContext
import com.postsaimanager.core.model.ExtractedData
import java.time.LocalDate

/**
 * Builds the pipeline's result from a Gemma reading that [GemmaReadingVerifier] already checked: the Gemma path's one verifier's second
 * half. It is *not* the scoring reading's [com.postsaimanager.core.domain.extraction.v2.SelectionVerifier], whose caps (a quote is
 * never above 0.6, a name from a position 0.65 ...) were made for a reader that scores without seeing the page, and which would put a
 * sender the model named by a line of the letter under the 0.75 threshold the contact and organisation linking need.
 *
 * What sets a value's confidence here is the model's decision (it chose the value with the page in view: HIGH) held to the checks that
 * can fail on the page's text alone: a quote the letter holds only after normalising or roughly, a value the OCR character repair
 * touched. Whatever the model chose and a check refused is not dropped silently: it is a value to check ([GemmaReadingVerifier.toCheck]),
 * blocked and below the review threshold with its reason in the notes, and the reasons of all losses make the reading need review.
 *
 * @param reading the checked reading of the answer being verified (the interpreter's decision); when null the raw answer is taken as it is
 */
class GemmaResultVerifier(
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val reading: () -> VerifiedReading?,
) : ResultVerifier {

    override fun verify(raw: RawInterpretation, text: RawText?, context: VerificationContext): ExtractionV2Result =
        Run(schema, raw, context, reading()).execute()

    private class Run(
        private val schema: ExtractionSchema,
        private val raw: RawInterpretation,
        private val ctx: VerificationContext,
        private val checked: VerifiedReading?,
    ) {
        private val rejections = mutableListOf<String>()
        private val usedCandidateIds = mutableSetOf<String>()
        private var anchor: LocalDate? = ctx.candidates.letterDate

        fun execute(): ExtractionV2Result {
            val type = schema.family(raw.type)
            if (type == null) rejections += "type '${raw.type}' is not in the schema"
            val docType = type ?: schema.abstain

            val slots = LinkedHashMap<SlotKey, SlotValue>()
            for ((key, answer) in raw.slots) {
                val slot = schema.allSlots.firstOrNull { it.json == key }
                if (slot == null) {
                    rejections += "slot '$key' is not in the schema"
                    continue
                }
                slotValue(slot, answer)?.let { slots[slot] = it }
            }
            if (anchor == null) anchor = slots.entries.firstOrNull { (k, _) -> k.canonical == Canonical.DOCUMENT_DATE }
                ?.value?.normalized?.let(::parseDate)?.takeIf { it.year in MIN_YEAR..MAX_YEAR }

            val parties = parties()
            val extras = extras(slots.values.mapNotNull { it.candidateId }.toSet())
            val losses = checked?.losses.orEmpty()
            return ExtractionV2Result(
                documentType = docType,
                typeConfidence = ConfidenceCombiner.aiScore(raw.typeConfidence),
                language = raw.language?.trim()?.lowercase()?.ifBlank { null },
                slots = slots,
                parties = Parties(applyToCheck(parties)),
                freeText = FreeText(),
                extras = extras,
                letterDate = anchor,
                addresses = raw.addresses,
                senderAddressAlternatives = raw.senderAddressAlternatives,
                topics = emptyList(),
                layoutTemplate = raw.layoutTemplate,
                diagnostics = Diagnostics(
                    candidateCount = ctx.candidates.candidates.size,
                    offeredCount = ctx.offered.size,
                    offeredDropped = ctx.offered.dropped.mapKeys { it.key.name },
                    modelCalled = true,
                    modelUsed = true,
                    rawAnswer = ctx.rawAnswer,
                    truncated = raw.truncated,
                    rejections = rejections + checked?.drops.orEmpty(),
                    conflicts = losses,
                    layoutCharsSent = ctx.layoutCharsSent,
                    layoutCharsTotal = ctx.layoutCharsTotal,
                    pagesRead = ctx.pagesRead,
                    totalPages = ctx.totalPages,
                    grammar = ctx.grammar,
                    prompt = ctx.prompt,
                ),
            )
        }

        // ── slots ──

        private fun slotValue(slot: SlotKey, answer: RawSlot): SlotValue? {
            if (slot.kind !in CANDIDATE_SLOTS) {
                rejections += "${slot.json}: a ${slot.kind} slot is not read from a Gemma answer"
                return null
            }
            val id = answer.id?.trim()?.takeIf { it.isNotEmpty() && it != StructuredGrammar.NONE } ?: return null
            val c = ctx.offered.get(id)
            if (c == null) {
                rejections += "${slot.json}: '$id' is not an offered candidate"
                return null
            }
            usedCandidateIds += c.id
            val value = candidateValue(c, answer.confidence, answer.role).copy(slot = slot)
            return withMeaning(value, answer.meaning)
        }

        /** The meaning the model gave, kept when the registry knows it for this kind of value (see the scoring reading's rule). */
        private fun withMeaning(value: SlotValue, meaning: String?): SlotValue {
            if (meaning == null) return value
            val known = ValueMeanings.DEFAULT.byId(meaning) ?: return value.also { rejections += "meaning '$meaning' is not in the registry" }
            if (known.kind != value.slot?.kind?.let(MeaningKind::of)) return value.also { rejections += "meaning '$meaning' does not fit ${value.slot?.json}" }
            return value.copy(meaning = known.id)
        }

        /** A value the model chose by candidate: its confidence is the model's word, capped only by what the page's text itself says wrong. */
        private fun candidateValue(c: Candidate, aiWord: String?, role: String?): SlotValue {
            val checks = mutableListOf<Check>()
            (c.validation as? Validation.Invalid)?.let { checks += Check.Cap(ConfidenceCombiner.Caps.INVALID, "failed a check: ${it.reason}") }
            if (c.attrs["repaired"] != null) {
                checks += Check.Cap(ConfidenceCombiner.Caps.REPAIRED, "read with an OCR character repair (${c.raw.trim()} -> ${c.normalized})", blocking = false)
            }
            if (c.attrs["unnormalized"] != null) checks += quoteCheck(QuoteVerifier.verify(c.raw, ctx.ocrText)?.match ?: QuoteMatch.FUZZY)
            return build(c, aiWord, role, checks)
        }

        private fun build(c: Candidate, aiWord: String?, role: String?, checks: List<Check>): SlotValue {
            val ai = ConfidenceCombiner.aiScore(aiWord)
            val combined = ConfidenceCombiner.combine(ai, checks)
            return SlotValue(
                slot = null, candidateId = c.id, value = c.raw.trim(), normalized = c.normalized,
                page = c.page, bbox = c.bbox, evidence = c.evidence, origin = SlotOrigin.MODEL_CHOICE,
                aiConfidence = ai, confidence = combined.final, validation = c.validation,
                blocked = combined.blocked, notes = combined.notes, role = role,
            )
        }

        /** A quote that is in the letter is the model's reading of it: an exact one stands, a normalised one hardly less, a rough one is to check. */
        private fun quoteCheck(match: QuoteMatch): Check = when (match) {
            QuoteMatch.EXACT -> Check.Pass
            QuoteMatch.NORMALIZED -> Check.Cap(NORMALIZED_QUOTE, "quote matches after normalising", blocking = false)
            QuoteMatch.FUZZY -> Check.Cap(FUZZY_QUOTE, "quote only roughly matches the letter")
        }

        // ── parties ──

        private fun parties(): List<Party> {
            val out = mutableListOf<Party>()
            for (rp in raw.parties) {
                val role = PartyRole.entries.firstOrNull { it.name == rp.role.trim().uppercase() }
                if (role == null) {
                    rejections += "party role '${rp.role}' is not one of ${PartyRole.entries.map { it.name }}"
                    continue
                }
                if (out.any { it.role == role }) continue // the first party of a role is the party
                val value = resolveName(rp) ?: continue
                val kind = PartyKind.entries.firstOrNull { it.name == rp.kind?.trim()?.uppercase() } ?: PartyKind.OTHER
                out += Party(role, kind, PartyRelation.NONE, value.copy(role = role.name))
            }
            return out
        }

        /** [rp]'s id is a name candidate, or a line of the letter quoted (found again in its text); anything else is dropped. */
        private fun resolveName(rp: RawParty): SlotValue? {
            val ref = rp.id.trim()
            val c = ctx.offered.get(ref)
            if (c != null) {
                if (c.kind != CandidateKind.NAME) {
                    rejections += "${rp.role}: $ref is a ${c.kind}, not a name"
                    return null
                }
                usedCandidateIds += c.id
                return build(c, rp.confidence, null, emptyList())
            }
            val verified = QuoteVerifier.verify(ref, ctx.ocrText)
            if (verified == null) {
                rejections += "${rp.role}: the name '${ref.take(MAX_LOGGED_CHARS)}' is not in the letter"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(rp.confidence)
            val combined = ConfidenceCombiner.combine(ai, listOf(quoteCheck(verified.match)))
            return quoted(ref, ai, combined.final, combined.blocked, combined.notes, verified.match)
        }

        private fun quoted(text: String, ai: Float, confidence: Float, blocked: Boolean, notes: List<String>, match: QuoteMatch?) = SlotValue(
            slot = null, candidateId = null, value = text, normalized = text, page = pageOf(text), bbox = null, evidence = text,
            origin = SlotOrigin.MODEL_QUOTED, aiConfidence = ai, confidence = confidence, validation = Validation.Unchecked,
            blocked = blocked, notes = notes, quoteMatch = match,
        )

        /** The parties the model named by a line the letter does not hold: kept, blocked and below the review threshold, with the reason. */
        private fun applyToCheck(parties: List<Party>): List<Party> {
            val extra = checked?.toCheck.orEmpty().mapNotNull { item ->
                val p = item.party ?: return@mapNotNull null
                if (parties.any { it.role == p.role }) return@mapNotNull null
                val value = quoted(p.reference, ConfidenceCombiner.UNKNOWN, TO_CHECK, true, listOf(item.reason), null)
                Party(p.role, p.kind, PartyRelation.NONE, value.copy(role = p.role.name))
            }
            return parties + extra
        }

        // ── extras ──

        private fun extras(taken: Set<String>): List<ExtraValue> {
            val used = (taken + usedCandidateIds).toMutableSet()
            val out = mutableListOf<ExtraValue>()
            for (x in raw.extras) {
                if (out.size >= StructuredGrammar.MAX_EXTRAS) break
                val printed = x.label.trim()
                if (printed.isEmpty()) continue
                val label = if (printed.any { it.isLetter() }) printed else unnamedLabel(x, out)
                val value = extraValue(x, used) ?: continue
                val extra = ExtraValue(label, keyOf(x.key), value)
                if (out.any { it.identity == extra.identity || (it.value.normalized == value.normalized && it.key == extra.key) }) continue
                out += extra
            }
            // What a check refused is still shown, as a value to check with the reason: never a silent empty.
            for (item in checked?.toCheck.orEmpty()) {
                val c = item.value ?: continue
                if (out.size >= StructuredGrammar.MAX_EXTRAS || c.id in used) continue
                used += c.id
                val label = c.label.trim().takeIf { l -> l.any { it.isLetter() } } ?: unnamedLabel(RawExtra("", c.kind.name.lowercase(), c.id, c.raw), out)
                val value = build(c, null, null, emptyList()).copy(confidence = TO_CHECK, blocked = true, notes = listOf(item.reason))
                val extra = ExtraValue(label, keyOf(c.kind.name.lowercase()), value)
                if (out.none { it.identity == extra.identity }) out += extra
            }
            return out
        }

        private fun unnamedLabel(x: RawExtra, taken: List<ExtraValue>): String {
            val base = ExtractedData.EXTRA_KEY_PREFIX + keyOf(x.key)
            var label = base
            var n = 1
            while (taken.any { it.label == label }) label = base + "_" + (++n)
            return label
        }

        private fun extraValue(x: RawExtra, used: MutableSet<String>): SlotValue? {
            val id = x.id.trim()
            if (id.isNotEmpty() && id != StructuredGrammar.NONE) {
                val c = ctx.offered.get(id)
                if (c == null) {
                    rejections += "extra '${x.label}': '$id' is not an offered candidate"
                    return null
                }
                if (id in used) return null
                used += id
                return candidateValue(c, x.confidence, null)
            }
            val q = x.value.trim()
            val verified = if (q.isEmpty()) null else QuoteVerifier.verify(q, ctx.ocrText)
            if (verified == null) {
                rejections += "extra '${x.label}' has neither a candidate nor a quote the letter holds"
                return null
            }
            val ai = ConfidenceCombiner.aiScore(x.confidence)
            val combined = ConfidenceCombiner.combine(ai, listOf(quoteCheck(verified.match)))
            return quoted(q, ai, combined.final, combined.blocked, combined.notes, verified.match)
        }

        private fun keyOf(key: String): String {
            val k = key.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
            return if (Regex("[a-z][a-z0-9_]{1,29}").matches(k)) k else "other"
        }

        private fun pageOf(text: String): Int? {
            for ((i, page) in ctx.pageTexts.withIndex()) if (QuoteVerifier.verify(text, page) != null) return i + 1
            return null
        }

        private fun parseDate(normalized: String): LocalDate? = runCatching { LocalDate.parse(normalized.take(DATE_CHARS)) }.getOrNull()
    }

    companion object {
        /** What a quote that matches the letter's text only after normalising it may reach: above the 0.75 threshold the linking needs. */
        const val NORMALIZED_QUOTE = 0.85f

        /** A quote that matches only roughly: shown, but to check. */
        const val FUZZY_QUOTE = 0.6f

        /** What a value the model chose and a check refused is shown at: visible (not hidden), blocked, below the review threshold. */
        const val TO_CHECK = 0.5f

        private val CANDIDATE_SLOTS = setOf(SlotKind.AMOUNT, SlotKind.DATE, SlotKind.DEADLINE, SlotKind.IBAN, SlotKind.REFERENCE)
        private const val MIN_YEAR = 2000
        private const val MAX_YEAR = 2100
        private const val MAX_LOGGED_CHARS = 40
        private const val DATE_CHARS = 10
    }
}
