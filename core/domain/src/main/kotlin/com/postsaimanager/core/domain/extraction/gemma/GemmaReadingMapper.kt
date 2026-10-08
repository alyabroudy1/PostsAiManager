package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.DocFamily
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.RawExtra
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawParty
import com.postsaimanager.core.domain.extraction.v2.RawSlot
import com.postsaimanager.core.domain.extraction.v2.Roles
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.StructuredGrammar

/**
 * Maps what survived the checks ([VerifiedReading]) onto [RawInterpretation], the type the pipeline's verifier, adapter and storage
 * already take: so the document list, the Extracted tab, the timeline, the contacts and the chat read a Gemma reading exactly as
 * they read any other.
 *
 * - The category the model chose becomes the family of the schema (the first family of the category that fits the document's
 *   direction), "document" the neutral abstain family; a category a person gave wins.
 * - Each party becomes a [RawParty]: a candidate id, or the printed line as a quote the verifier finds in the text again.
 * - A date or an amount the model gave a meaning goes to the slot [MeaningSlots] names for that meaning (the best of the meanings that
 *   share a slot), with the meaning kept on the value; every other one is an open value under the label printed beside it.
 * - A reference goes to the slot of its kind (the account to `iban`), or is an open value. (The key facts, open values too, come with the
 *   second step, [GemmaTextWriter].)
 *
 * Nothing here decides what a value means: the model did, and the registries say where the meaning is stored.
 */
class GemmaReadingMapper(
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT,
) {

    /** The mapped reading and the family it chose (for the title and the trace). */
    class Mapped(val raw: RawInterpretation, val family: DocFamily?)

    fun map(reading: VerifiedReading, language: String?, direction: DocDirection, forcedFamily: String?): Mapped {
        val family = familyOf(reading.category, direction, forcedFamily)
        val slots = LinkedHashMap<String, RawSlot>()
        val extras = ArrayList<RawExtra>()

        placeValues(reading.dates, MeaningKind.DATE, slots, extras)
        placeValues(reading.amounts, MeaningKind.AMOUNT, slots, extras)
        for (r in reading.references) {
            val slot = schema.allSlots.firstOrNull { it.json == r.kind && it.kind in REFERENCE_KINDS }
            if (slot != null && slots[slot.json] == null) {
                slots[slot.json] = RawSlot(id = r.candidate.id, confidence = CONFIDENT)
            } else {
                extras += extra(r.candidate, r.kind.takeIf { it != GemmaVocabulary.OTHER } ?: r.candidate.kind.name.lowercase())
            }
        }

        val parties = reading.parties.map { p ->
            RawParty(role = p.role.name, id = p.reference, kind = p.kind.name, relation = null, confidence = CONFIDENT)
        }
        val given = forcedFamily?.let(schema::family)
        val raw = RawInterpretation(
            type = family?.id ?: ExtractionSchema.FREE_FORM.id,
            typeConfidence = if (given != null) CONFIDENT else FAIRLY_CONFIDENT,
            language = language,
            parties = parties.take(StructuredGrammar.MAX_PARTIES),
            slots = slots,
            extras = extras.take(StructuredGrammar.MAX_EXTRAS),
            universalSlots = true,
        )
        return Mapped(raw, family)
    }

    /** The family of [category] for a document of [direction]; the abstain family for "document"; a person's own family wins. */
    fun familyOf(category: String, direction: DocDirection, forcedFamily: String?): DocFamily? {
        forcedFamily?.let(schema::family)?.let { return it }
        val chosen = vocab.category(category)
        val fitting = chosen?.families?.firstNotNullOfOrNull { id -> schema.family(id)?.takeIf { it.scored && direction in it.directions } }
        return fitting ?: schema.abstain
    }

    /**
     * Fills the slots the values' meanings name, in priority order; a value whose slot is taken, whose meaning has no slot or whose
     * meaning is "other" is an open value.
     */
    private fun placeValues(values: List<VerifiedValue>, kind: MeaningKind, slots: MutableMap<String, RawSlot>, extras: MutableList<RawExtra>) {
        val ordered = values.sortedBy { v -> v.meaningId?.let { MeaningSlots.priority(kind, it) } ?: Int.MAX_VALUE }
        for (v in ordered) {
            val slot = v.meaningId?.let { MeaningSlots.slotOf(kind, it) }?.let { key -> schema.allSlots.firstOrNull { it.json == key } }
            if (slot != null && slots[slot.json] == null) {
                slots[slot.json] = RawSlot(id = v.candidate.id, role = roleOf(slot), confidence = CONFIDENT, meaning = v.meaningId)
            } else {
                extras += extra(v.candidate, v.meaningId?.lowercase()?.replace('_', ' ') ?: v.candidate.kind.name.lowercase())
            }
        }
    }

    /** An open value: the label printed beside it, else [fallbackLabel]; the key is the kind of the value, only metadata. */
    private fun extra(c: Candidate, fallbackLabel: String): RawExtra =
        RawExtra(label = c.label.ifBlank { fallbackLabel }, key = keyOf(c.kind, fallbackLabel), id = c.id, value = c.raw, confidence = CONFIDENT)

    private fun keyOf(kind: CandidateKind, label: String) = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { kind.name.lowercase() }

    private companion object {
        const val CONFIDENT = "HIGH"
        const val FAIRLY_CONFIDENT = "MEDIUM"
        val REFERENCE_KINDS = setOf(SlotKind.REFERENCE, SlotKind.IBAN)
    }
}

/** The role a value has by sitting in [slot]: the slot's own expected role in the schema's order, as the scoring reading gives it. */
internal fun roleOf(slot: SlotKey): String {
    val order = if (slot.kind == SlotKind.AMOUNT) Roles.AMOUNT else Roles.DATE
    return order.firstOrNull { it in slot.expects } ?: "OTHER"
}
