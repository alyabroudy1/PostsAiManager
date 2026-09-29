package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EntityKind
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FactKind
import com.postsaimanager.core.model.RecognisedFact

/**
 * Turns what the model understood into the field slots the app already stores.
 *
 * ### Why the names match the regex extractor's
 *
 * A field is identified by `(documentId, fieldName)`. Switching a document from pattern
 * matching to the model must land on the *same* slots, or every old field is orphaned and
 * deleted while a parallel set appears beside it — and any correction the user made to
 * "Sender Organization" would be stranded on a slot nothing writes to any more. The
 * canonical names below are the price of that continuity.
 *
 * ### Why mentioned people are not fields
 *
 * A slot is unique per document, so two people mentioned in one letter cannot both be
 * "Mentioned Person" — the second would overwrite the first, or the unique index would
 * reject it. They belong in profiles and links, which is where [DocumentUnderstanding.entities]
 * is consumed. Flattening them into numbered fields would produce slots whose meaning
 * changes as the model's ordering changes, and a correction to "Mentioned Person 2" would
 * silently attach to a different person on the next run.
 */
object UnderstandingToFields {

    fun invoke(
        documentId: String,
        understanding: DocumentUnderstanding,
        newId: (String) -> String,
    ): List<ExtractedData> {
        val fields = mutableListOf<ExtractedData>()

        fun add(name: String, value: String, type: ExtractedFieldType, confidence: Float) {
            if (value.isBlank()) return
            fields += ExtractedData(
                id = newId("$documentId#$name"),
                documentId = documentId,
                fieldName = name,
                fieldValue = value.trim(),
                fieldType = type,
                confidence = confidence.coerceIn(0f, 1f),
            )
        }

        understanding.entities
            .firstOrNull { it.role == EntityRole.SENDER }
            ?.let { sender ->
                val isOrganisation = sender.kind == EntityKind.AUTHORITY ||
                    sender.kind == EntityKind.COMPANY
                add(
                    name = if (isOrganisation) SENDER_ORGANISATION else SENDER_NAME,
                    value = sender.name,
                    type = if (isOrganisation) {
                        ExtractedFieldType.ORGANIZATION
                    } else {
                        ExtractedFieldType.PERSON_NAME
                    },
                    confidence = sender.confidence,
                )
            }

        understanding.entities
            .firstOrNull { it.role == EntityRole.RECIPIENT }
            ?.let { add(RECEIVER_NAME, it.name, ExtractedFieldType.PERSON_NAME, it.confidence) }

        understanding.entities
            .firstOrNull { it.role == EntityRole.SENDER_CONTACT }
            ?.let { add(CONTACT_PERSON, it.name, ExtractedFieldType.PERSON_NAME, it.confidence) }

        if (understanding.subject.isNotBlank()) {
            add(SUBJECT, understanding.subject, ExtractedFieldType.SUBJECT, 0.9f)
        }

        // Two facts can share a label — a letter quoting several dates, say. The slot is
        // unique, so the most confident wins rather than the last one parsed.
        understanding.facts
            .filter { it.value.isNotBlank() }
            .groupBy { canonicalLabel(it) }
            .forEach { (label, candidates) ->
                val best = candidates.maxBy { it.confidence }
                add(label, best.value, typeOf(best.kind), best.confidence)
            }

        return fields
    }

    /**
     * Keeps the model's own label unless it names something the app already has a slot for.
     *
     * The model writes "Frist" or "Deadline" depending on the letter's language; both must
     * reach the one slot that reminders are built from, or a German letter and an English
     * one about the same obligation would be stored as different things.
     */
    private fun canonicalLabel(fact: RecognisedFact): String = when (fact.kind) {
        FactKind.DEADLINE -> DEADLINE
        FactKind.DATE -> DOCUMENT_DATE
        FactKind.AMOUNT -> AMOUNT
        FactKind.IBAN -> IBAN
        FactKind.SUBJECT -> SUBJECT
        // References keep their own label: "Aktenzeichen" and "Ihr Zeichen" are genuinely
        // different references and collapsing them would lose one.
        FactKind.REFERENCE, FactKind.OTHER -> fact.label.trim().ifBlank { "Reference" }
    }

    private fun typeOf(kind: FactKind): ExtractedFieldType = when (kind) {
        FactKind.REFERENCE -> ExtractedFieldType.REFERENCE_NUMBER
        FactKind.DATE -> ExtractedFieldType.DATE
        FactKind.DEADLINE -> ExtractedFieldType.DEADLINE
        FactKind.AMOUNT -> ExtractedFieldType.OTHER
        FactKind.IBAN -> ExtractedFieldType.IBAN
        FactKind.SUBJECT -> ExtractedFieldType.SUBJECT
        FactKind.OTHER -> ExtractedFieldType.OTHER
    }

    // Canonical slot names. Shared with the regex extractor on purpose — see the class note.
    const val SENDER_NAME = "Sender Name"
    const val SENDER_ORGANISATION = "Sender Organization"
    const val RECEIVER_NAME = "Receiver Name"
    const val CONTACT_PERSON = "Contact Person"
    const val SUBJECT = "Subject"
    const val DEADLINE = "Deadline"
    const val DOCUMENT_DATE = "Document Date"
    const val AMOUNT = "Amount"
    const val IBAN = "IBAN"
}
