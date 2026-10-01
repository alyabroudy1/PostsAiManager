package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.FieldProvenance
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
 * - Without a model the found values become fields keyed `found:KIND:N` with low confidence and no role.
 */
class ExtractionV2Adapter : UnderstandingAdapter {

    override fun adapt(result: ExtractionV2Result): DocumentUnderstanding {
        val facts = mutableListOf<RecognisedFact>()
        val usedLabels = mutableSetOf<String>()

        fun add(label: String, value: String, kind: FactKind, confidence: Float, provenance: FieldProvenance? = null) {
            if (value.isBlank()) return
            facts += RecognisedFact(label, value.trim(), kind, confidence.coerceIn(0f, 1f), provenance)
            usedLabels += label.lowercase()
        }

        val type = result.documentType
        if (type != null) {
            val canonicalTaken = mutableSetOf<Canonical>()
            for (slot in type.slots) {
                val v = result.slots[slot]
                if (v != null) {
                    val canonical = slot.canonical?.takeIf { canonicalTaken.add(it) }
                    val p = provenanceOf(slot.json, v)
                    when (canonical) {
                        Canonical.AMOUNT -> add(AMOUNT, v.value, FactKind.AMOUNT, v.confidence, p)
                        Canonical.DEADLINE -> add(DEADLINE, v.value, FactKind.DEADLINE, v.confidence, p)
                        Canonical.DOCUMENT_DATE -> add(DOCUMENT_DATE, v.value, FactKind.DATE, v.confidence, p)
                        Canonical.IBAN -> add(IBAN, v.value, FactKind.IBAN, v.confidence, p)
                        null -> add(slot.label, v.value, if (slot.kind == SlotKind.REFERENCE) FactKind.REFERENCE else FactKind.OTHER, v.confidence, p)
                    }
                }
                result.slotLists[slot]?.takeIf { it.isNotEmpty() }?.let { list ->
                    add(
                        slot.label, list.joinToString(", ") { it.value }, FactKind.REFERENCE, list.minOf { it.confidence },
                        provenanceOf(slot.json, list.first()).copy(aiConfidence = list.minOf { it.aiConfidence }),
                    )
                }
            }
        }

        result.freeText.subject?.let { add(SUBJECT, it.value, FactKind.SUBJECT, it.confidence, provenanceOf(SUBJECT_KEY, it)) }
        // The summary is not a field: it goes to the document (DocumentUnderstanding.summary), marked as the AI's.

        for (x in result.extras) {
            // A name is unique per document, so a label that collides with a field already present would
            // overwrite it. The extra is then stored under its slot key (its identity, unique by construction);
            // the printed label is never altered, and the screen shows the key's words for it.
            val label = if (x.label.lowercase() in usedLabels) x.identity else x.label
            add(label, x.value.value, FactKind.OTHER, x.value.confidence, provenanceOf(x.identity, x.value))
        }

        // The structured addresses: one row per part (`addressee.street`, `sender.postcode`...), next to the unchanged party name rows.
        for ((role, address) in result.addresses) {
            for (row in AddressRows.rows(role, address)) {
                add(
                    row.key, row.value, FactKind.OTHER, row.confidence,
                    FieldProvenance(slotKey = row.key, role = row.role.name, origin = AddressRows.ORIGIN, page = row.page, bbox = row.bbox),
                )
            }
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
            readingTrace = result.diagnostics.trace,
            enrichment = result.enrichment,
        )
    }

    private fun provenanceOf(slotKey: String, v: SlotValue) = FieldProvenance(
        slotKey = slotKey,
        role = v.role,
        origin = v.origin.name,
        aiConfidence = v.aiConfidence,
        evidence = v.evidence.takeIf { it.isNotBlank() },
        page = v.page,
        bbox = v.bbox,
        // TODO(P4): fill from the interpreter's runner-up candidates; a SlotValue carries none yet.
        alternatives = emptyList(),
    )

    /**
     * The single owner of found values: each is stored under a slot key ([foundKey]) that is also its
     * name, so no English is written as data; the screen renders "Found date 1" from the key.
     */
    private fun addFound(result: ExtractionV2Result, add: (String, String, FactKind, Float, FieldProvenance?) -> Unit) {
        val counters = HashMap<CandidateKind, Int>()
        for (c in result.foundValues) {
            val kind = foundKindOf(c.kind) ?: continue
            val n = (counters[kind] ?: 0) + 1
            counters[kind] = n
            val key = foundKey(kind, n)
            add(
                key, c.raw, FactKind.OTHER, FOUND_CONFIDENCE,
                FieldProvenance(
                    slotKey = key, origin = FOUND_ORIGIN, page = c.page, bbox = c.bbox,
                    evidence = c.evidence.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private fun entities(result: ExtractionV2Result): List<RecognisedEntity> {
        val parties = result.parties
        val out = mutableListOf<RecognisedEntity>()
        val emitted = mutableSetOf<String>()
        val named = mutableSetOf<String>()

        fun add(name: String, kind: EntityKind, role: EntityRole, relation: String, p: Party, slotKey: String?) {
            if (name.isBlank() || !emitted.add("$role:${name.lowercase()}")) return
            // Someone already listed as the sender or a recipient is not listed again as "mentioned".
            if (role == EntityRole.MENTIONED && name.lowercase() in named) return
            named += name.lowercase()
            val v = p.value
            out += RecognisedEntity(
                name.trim(), kind, role, relation, v.confidence.coerceIn(0f, 1f),
                provenanceOf(slotKey ?: "", v).copy(slotKey = slotKey, role = p.role.name),
            )
        }

        parties.sender?.let { add(it.name, kindOf(it.kind), EntityRole.SENDER, "", it, SENDER_KEY) }

        val subjects = parties.subjectPersons
        for (a in parties.allAddressees) {
            // A guardian is addressed on behalf of the subject person; the profile to link is the subject.
            // The stored evidence is the addressee's, the printed name the letter was sent to.
            val stand = if (a.relation == PartyRelation.GUARDIAN_OF) subjects.firstOrNull() else null
            val key = ADDRESSEE_KEY
            if (stand != null) {
                add(stand.name, kindOf(stand.kind), EntityRole.RECIPIENT, "child; letter addressed to the guardian", a, key)
            } else {
                add(a.name, kindOf(a.kind), EntityRole.RECIPIENT, if (a.relation == PartyRelation.HOUSEHOLD) "household" else "", a, key)
            }
        }
        parties.routingPerson?.let {
            add(it.name, kindOf(it.kind), EntityRole.MENTIONED, "contact at the addressee", it, null)
        }
        parties.careOf?.let {
            add(it.name, kindOf(it.kind), EntityRole.MENTIONED, "care of (mailbox)", it, null)
        }
        for (s in subjects) {
            add(s.name, kindOf(s.kind), EntityRole.MENTIONED, "subject of the letter", s, null)
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

        // Slot keys of the values that are not schema slots; UnderstandingToFields stores them.
        const val SENDER_KEY = "sender"
        const val ADDRESSEE_KEY = "addressee"
        const val SUBJECT_KEY = "subject"

        /** Slot keys of found values start with this, then the kind and the number: `found:DATE:1`. */
        const val FOUND_KEY_PREFIX = "found:"

        /** The kind a found value is filed under (dates and date-times together), or null for a kind that is not stored. */
        fun foundKindOf(kind: CandidateKind): CandidateKind? = when (kind) {
            CandidateKind.DATE, CandidateKind.DATETIME -> CandidateKind.DATE
            CandidateKind.AMOUNT, CandidateKind.IBAN, CandidateKind.REFERENCE, CandidateKind.PHONE, CandidateKind.EMAIL -> kind
            else -> null
        }

        fun foundKey(kind: CandidateKind, number: Int) = "$FOUND_KEY_PREFIX${kind.name}:$number"

        /** The kind and number in a [foundKey], or null when [key] is not one. */
        fun parseFoundKey(key: String?): Pair<CandidateKind, Int>? {
            if (key == null || !key.startsWith(FOUND_KEY_PREFIX)) return null
            val parts = key.removePrefix(FOUND_KEY_PREFIX).split(':')
            if (parts.size != 2) return null
            val kind = CandidateKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val number = parts[1].toIntOrNull() ?: return null
            return kind to number
        }

        /** [FieldProvenance.origin] of a value code found and nobody chose. */
        const val FOUND_ORIGIN = "FOUND"

        /** Found values have no role; low on purpose so they show as worth checking. */
        const val FOUND_CONFIDENCE = 0.3f
    }
}
