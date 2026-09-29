package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.InputTruncation
import com.postsaimanager.core.model.RecognisedEntity
import com.postsaimanager.core.model.RecognisedFact

/**
 * Maps an [ExtractionV2Result] onto [DocumentUnderstanding], the shape the pipeline, the merge and
 * the UI already use, until workstream E replaces it.
 *
 * - The slots that feed an existing field (amount, deadline, document date, IBAN) take its name
 *   ("Amount", "Deadline", "Document Date", "IBAN"); the first one present in the type's slot order
 *   wins the name, and the others keep their own labels, so several amounts are several fields.
 * - Extras keep the model's own label, as printed on the page.
 * - The confidence carried is the final one from [ConfidenceCombiner].
 * - Without a model the found values become "Found ..." fields with low confidence and no role.
 */
class ExtractionV2Adapter : UnderstandingAdapter {

    override fun adapt(result: ExtractionV2Result): DocumentUnderstanding {
        val facts = mutableListOf<RecognisedFact>()
        val usedLabels = mutableSetOf<String>()

        fun add(label: String, value: String, kind: FactKind, confidence: Float) {
            if (value.isBlank()) return
            facts += RecognisedFact(label, value.trim(), kind, confidence.coerceIn(0f, 1f))
            usedLabels += label.lowercase()
        }

        val type = result.documentType
        if (type != null) {
            val canonicalTaken = mutableSetOf<Canonical>()
            for (slot in type.slots) {
                val v = result.slots[slot]
                if (v != null) {
                    val canonical = slot.canonical?.takeIf { canonicalTaken.add(it) }
                    when (canonical) {
                        Canonical.AMOUNT -> add(AMOUNT, v.value, FactKind.AMOUNT, v.confidence)
                        Canonical.DEADLINE -> add(DEADLINE, v.value, FactKind.DEADLINE, v.confidence)
                        Canonical.DOCUMENT_DATE -> add(DOCUMENT_DATE, v.value, FactKind.DATE, v.confidence)
                        Canonical.IBAN -> add(IBAN, v.value, FactKind.IBAN, v.confidence)
                        null -> add(slot.label, v.value, if (slot.kind == SlotKind.REFERENCE) FactKind.REFERENCE else FactKind.OTHER, v.confidence)
                    }
                }
                result.slotLists[slot]?.takeIf { it.isNotEmpty() }?.let { list ->
                    add(slot.label, list.joinToString(", ") { it.value }, FactKind.REFERENCE, list.minOf { it.confidence })
                }
            }
        }

        result.freeText.subject?.let { add(SUBJECT, it.value, FactKind.SUBJECT, it.confidence) }
        result.freeText.summary?.let { add(CONTENT_PREVIEW, it.value, FactKind.OTHER, it.confidence) }

        for (x in result.extras) {
            // A label that collides with a field already present would overwrite it in the merge.
            val label = if (x.label.lowercase() in usedLabels) "${x.label} (${x.key})" else x.label
            add(label, x.value.value, FactKind.OTHER, x.value.confidence)
        }

        if (!result.diagnostics.modelUsed) addFound(result, ::add)

        return DocumentUnderstanding(
            language = result.language.orEmpty(),
            documentType = type?.id.orEmpty(),
            documentTypeConfidence = result.typeConfidence,
            // The subject goes out as a fact so it carries its own confidence.
            subject = "",
            entities = entities(result),
            facts = facts,
            inputTruncation = truncation(result),
            title = result.freeText.title?.value.orEmpty(),
            summary = result.freeText.summary?.value.orEmpty(),
            suggestedQuestions = result.freeText.suggestedQuestions,
            modelUsed = result.diagnostics.modelUsed,
        )
    }

    private fun addFound(result: ExtractionV2Result, add: (String, String, FactKind, Float) -> Unit) {
        val counters = HashMap<String, Int>()
        for (c in result.foundValues) {
            val noun = when (c.kind) {
                CandidateKind.DATE, CandidateKind.DATETIME -> "date"
                CandidateKind.AMOUNT -> "amount"
                CandidateKind.IBAN -> "IBAN"
                CandidateKind.REFERENCE -> "reference"
                CandidateKind.PHONE -> "phone"
                CandidateKind.EMAIL -> "e-mail"
                else -> continue
            }
            val n = (counters[noun] ?: 0) + 1
            counters[noun] = n
            add("Found $noun $n", c.raw, FactKind.OTHER, FOUND_CONFIDENCE)
        }
    }

    private fun entities(result: ExtractionV2Result): List<RecognisedEntity> {
        val parties = result.parties
        val out = mutableListOf<RecognisedEntity>()
        val emitted = mutableSetOf<String>()

        fun add(name: String, kind: EntityKind, role: EntityRole, relation: String, confidence: Float) {
            if (name.isBlank() || !emitted.add("$role:${name.lowercase()}")) return
            out += RecognisedEntity(name.trim(), kind, role, relation, confidence.coerceIn(0f, 1f))
        }

        parties.sender?.let { add(it.name, kindOf(it.kind), EntityRole.SENDER, "", it.value.confidence) }

        val subjects = parties.subjectPersons
        for (a in parties.allAddressees) {
            // A guardian is addressed on behalf of the subject person; the profile to link is the subject.
            val stand = if (a.relation == PartyRelation.GUARDIAN_OF) subjects.firstOrNull() else null
            if (stand != null) {
                add(stand.name, kindOf(stand.kind), EntityRole.RECIPIENT, "child; letter addressed to the guardian", a.value.confidence)
            } else {
                add(a.name, kindOf(a.kind), EntityRole.RECIPIENT, if (a.relation == PartyRelation.HOUSEHOLD) "household" else "", a.value.confidence)
            }
        }
        parties.routingPerson?.let {
            add(it.name, kindOf(it.kind), EntityRole.MENTIONED, "contact at the addressee", it.value.confidence)
        }
        parties.careOf?.let {
            add(it.name, kindOf(it.kind), EntityRole.MENTIONED, "care of (mailbox)", it.value.confidence)
        }
        for (s in subjects) {
            add(s.name, kindOf(s.kind), EntityRole.MENTIONED, "subject of the letter", s.value.confidence)
        }
        return out
    }

    private fun kindOf(kind: PartyKind) = when (kind) {
        PartyKind.PERSON -> EntityKind.PERSON
        PartyKind.AUTHORITY -> EntityKind.AUTHORITY
        PartyKind.COMPANY -> EntityKind.COMPANY
        PartyKind.OTHER -> EntityKind.OTHER
    }

    private fun truncation(result: ExtractionV2Result): InputTruncation? {
        val d = result.diagnostics
        if (!d.modelCalled || d.layoutComplete) return null
        return InputTruncation(
            charactersRead = d.layoutCharsSent,
            totalCharacters = d.layoutCharsTotal,
            pagesRead = d.pagesRead,
            totalPages = d.totalPages,
        )
    }

    companion object {
        // The names the existing fields already use; see UnderstandingToFields.
        const val AMOUNT = "Amount"
        const val DEADLINE = "Deadline"
        const val DOCUMENT_DATE = "Document Date"
        const val IBAN = "IBAN"
        const val SUBJECT = "Subject"
        const val CONTENT_PREVIEW = "Content Preview"

        /** Found values have no role; low on purpose so they show as worth checking. */
        const val FOUND_CONFIDENCE = 0.3f
    }
}
