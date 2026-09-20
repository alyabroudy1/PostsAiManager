package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/** What kind of thing an entity is. Permanent — independent of any one document. */
@Serializable
enum class EntityKind {
    PERSON,

    /** Jobcenter, Finanzamt, Familienkasse, a court. */
    AUTHORITY,

    COMPANY,
    OTHER,
}

/**
 * What an entity is doing **in this document**.
 *
 * Separate from [EntityKind] on purpose. A Jobcenter *is* an authority permanently; it is
 * *the sender* only of the letters it sends. The same organisation may be merely mentioned
 * in a letter from someone else, and linking it as that letter's sender would be wrong.
 */
@Serializable
enum class EntityRole {
    SENDER,
    RECIPIENT,

    /** A person acting for the sender — the caseworker who signed it. */
    SENDER_CONTACT,

    /** Named in the body: a spouse, a child, a third party. */
    MENTIONED,
}

/**
 * A person or organisation the model recognised.
 *
 * [confidence] governs what happens next, not whether the value is stored: a high-confidence
 * entity is linked to a profile silently, a low-confidence one is proposed. Creating a
 * profile from a half-read name gives the user clutter they then have to find and undo,
 * which is a worse failure than a missing link.
 */
@Serializable
data class RecognisedEntity(
    val name: String,
    val kind: EntityKind,
    val role: EntityRole,
    /** Free text, e.g. "spouse of the recipient". Empty when there is none. */
    val relation: String = "",
    val confidence: Float,
)

/** What kind of fact a value is, so the app knows what it can do with it. */
@Serializable
enum class FactKind {
    REFERENCE,
    DATE,

    /** A date that obliges the user to act — becomes a reminder. */
    DEADLINE,

    AMOUNT,
    IBAN,
    SUBJECT,
    OTHER,
}

@Serializable
data class RecognisedFact(
    val label: String,
    val value: String,
    val kind: FactKind,
    val confidence: Float,
)

/**
 * One model's reading of one document.
 *
 * Everything here is a **claim**, not a fact — it is stored with `MACHINE` provenance, so a
 * wrong entity can be deleted and stays deleted, and a corrected value is never overwritten
 * by a later run. That is what makes it safe to let a model create things at all.
 */
@Serializable
data class DocumentUnderstanding(
    val language: String = "",
    val documentType: String = "",
    val subject: String = "",
    val entities: List<RecognisedEntity> = emptyList(),
    val facts: List<RecognisedFact> = emptyList(),
    /**
     * True when the model's answer was cut off before it finished, and this is only the
     * well-formed prefix that could be recovered — not the complete reading.
     *
     * A caller must not treat this the same as a normal result: entities and facts the
     * model would have gone on to find are simply absent here, not merely low-confidence,
     * and that is a different thing to tell the user than "the document has no deadline".
     * Left `false` on every ordinary result, including one where the model itself decided
     * there was nothing to report.
     */
    val truncated: Boolean = false,
) {
    val sender: RecognisedEntity? get() = entities.firstOrNull { it.role == EntityRole.SENDER }

    val deadline: RecognisedFact?
        get() = facts.firstOrNull { it.kind == FactKind.DEADLINE }

    /** Below this the app proposes rather than acts. See [RecognisedEntity.confidence]. */
    fun confident(threshold: Float = AUTO_LINK_CONFIDENCE): List<RecognisedEntity> =
        entities.filter { it.confidence >= threshold }

    fun needingReview(threshold: Float = AUTO_LINK_CONFIDENCE): List<RecognisedEntity> =
        entities.filter { it.confidence < threshold }

    companion object {
        /**
         * At or above this, an entity is linked without asking.
         *
         * Deliberately high. The cost of the two mistakes is asymmetric: a missing link is
         * one tap to add, while a wrongly created profile has to be found, understood and
         * deleted — and until then it pollutes every list it appears in.
         */
        const val AUTO_LINK_CONFIDENCE = 0.75f
    }
}
