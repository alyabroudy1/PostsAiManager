package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.AmountParser
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.Diagnostics
import com.postsaimanager.core.domain.extraction.v2.ExtraValue
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.extraction.v2.SlotValue
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.SummarySource
import java.time.LocalDate

/**
 * A reading of a letter the OCR could not read (an Arabic page, a blurred photo): the model had only the picture, so there is no line
 * to point at and no text to ground a value in. What it wrote is kept, never trusted: every value is marked "to check" (a confidence
 * below the review line, a note saying why), and only what code can still show wrong is dropped: a date that is not a calendar date,
 * an amount that does not parse, an account whose checksum is wrong, an id that is not in the registry.
 *
 * It goes straight to [ExtractionV2Result] (there are no candidates for the pipeline's verifier to check), so the adapter and
 * everything after it see a reading like any other.
 */
class GemmaImageOnly(
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT,
) {

    fun result(reading: GemmaReading, trace: List<String>, rawAnswer: String?, direction: DocDirection = DocDirection.INCOMING, forcedFamily: String? = null): ExtractionV2Result {
        val rejections = mutableListOf<String>()
        val category = reading.category?.trim()?.lowercase()?.takeIf { it in vocab.categoryIds } ?: GemmaVocabulary.DOCUMENT_CATEGORY
        val family = GemmaReadingMapper(schema, vocab).familyOf(category, direction, forcedFamily)

        fun value(slot: SlotKey?, text: String, normalized: String, meaning: String? = null, role: String? = null) = SlotValue(
            slot = slot, candidateId = null, value = text, normalized = normalized, page = null, bbox = null, evidence = "",
            origin = SlotOrigin.MODEL_GENERATED, aiConfidence = ConfidenceCombiner.MEDIUM, confidence = ConfidenceCombiner.MEDIUM,
            validation = Validation.Unchecked, notes = listOf(NOTE), role = role, meaning = meaning,
        )

        val slots = LinkedHashMap<SlotKey, SlotValue>()
        val extras = ArrayList<ExtraValue>()
        fun extra(label: String, key: String, text: String) {
            if (label.isNotBlank() && text.isNotBlank()) extras += ExtraValue(label.trim(), key, value(null, text.trim(), text.trim()))
        }

        // Dates and amounts: the best meaning of each slot wins it, as in the reading with lines.
        for ((values, kind) in listOf(reading.dates to MeaningKind.DATE, reading.amounts to MeaningKind.AMOUNT)) {
            val ordered = values.sortedBy { v -> MeaningSlots.priority(kind, v.meaning.orEmpty().uppercase()) }
            for (v in ordered) {
                val printed = v.value?.trim().orEmpty()
                val meaning = (if (kind == MeaningKind.DATE) vocab.dateMeaning(v.meaning) else vocab.amountMeaning(v.meaning))?.id
                val normalized = (if (kind == MeaningKind.DATE) isoDate(printed) else money(printed))
                if (normalized == null) {
                    rejections += "${kind.name.lowercase()} did not parse"
                    continue
                }
                val slot = meaning?.let { MeaningSlots.slotOf(kind, it) }?.let { key -> schema.allSlots.firstOrNull { it.json == key } }
                if (slot != null && slot !in slots) {
                    slots[slot] = value(slot, printed, normalized, meaning, roleOf(slot))
                } else {
                    extra(meaning?.lowercase()?.replace('_', ' ') ?: kind.name.lowercase(), meaning?.lowercase() ?: kind.name.lowercase(), printed)
                }
            }
        }
        for (r in reading.references) {
            val printed = r.value?.trim().orEmpty()
            val kind = r.meaning?.trim()?.takeIf { it in vocab.referenceKinds } ?: GemmaVocabulary.OTHER
            if (printed.isEmpty()) continue
            if (kind == GemmaVocabulary.IBAN_KIND && !IbanValidator.validate(printed).isValid) {
                rejections += "account failed its checksum"
                continue
            }
            val slot = schema.allSlots.firstOrNull { it.json == kind && it.kind in setOf(SlotKind.REFERENCE, SlotKind.IBAN) }
            if (slot != null && slot !in slots) {
                slots[slot] = value(slot, printed, if (slot.kind == SlotKind.IBAN) IbanValidator.compact(printed) else printed)
            } else {
                extra(kind.takeIf { it != GemmaVocabulary.OTHER } ?: "reference", kind, printed)
            }
        }
        reading.keyInfo.take(GemmaSchema.MAX_KEY_INFO).forEach { extra(it.label, "key_info", it.value) }

        val parties = listOf(
            PartyRole.SENDER to reading.sender, PartyRole.ADDRESSEE to reading.addressee,
            PartyRole.CONTACT to reading.contact, PartyRole.SUBJECT_PERSON to reading.subjectPerson,
        ).mapNotNull { (role, p) ->
            val name = p?.text?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = PartyKind.entries.firstOrNull { it.name.equals(p?.kind?.trim(), ignoreCase = true) } ?: PartyKind.OTHER
            Party(role, kind, PartyRelation.NONE, value(null, name, name, role = role.name))
        }
        val sender = parties.firstOrNull { it.role == PartyRole.SENDER }
        val clash = parties.firstOrNull { it.role == PartyRole.ADDRESSEE && sender != null && it.name.equals(sender.name, ignoreCase = true) }
        val kept = parties.filter { it !== clash }.also { if (clash != null) rejections += "the addressee is the sender" }

        val summary = reading.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { SummaryResult(it, SummarySource.MODEL, null, emptyList()) }
        val title = family?.let { TitleComposer.compose(it.id, sender?.name, reading.name?.trim()?.take(GemmaSchema.MAX_NAME_CHARS)) }

        return ExtractionV2Result(
            documentType = family,
            typeConfidence = ConfidenceCombiner.aiScore("MEDIUM"),
            language = reading.language?.trim()?.lowercase()?.takeIf { GemmaSchema.LANGUAGE_CODE.matches(it) },
            slots = slots,
            parties = Parties(kept),
            summary = summary,
            composedTitle = title,
            event = vocab.eventKind(reading.eventKind)?.let { EventReading(it) },
            extras = extras.take(MAX_EXTRAS),
            diagnostics = Diagnostics(
                candidateCount = 0, offeredCount = 0, modelCalled = true, modelUsed = true, rawAnswer = rawAnswer,
                rejections = rejections, trace = trace,
            ),
        )
    }

    private fun isoDate(text: String): String? = runCatching { LocalDate.parse(text.take(ISO_CHARS)) }.getOrNull()?.toString()

    private fun money(text: String): String? {
        val parts = text.trim().split(Regex("\\s+"))
        return AmountParser.parse(parts.firstOrNull().orEmpty(), parts.getOrNull(1))?.canonical()
    }

    private companion object {
        const val NOTE = "read from the picture only: not found in any text of the letter, please check"
        const val ISO_CHARS = 10
        const val MAX_EXTRAS = 6
    }
}
