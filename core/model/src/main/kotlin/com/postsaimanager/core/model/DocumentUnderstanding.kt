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
    val provenance: FieldProvenance? = null,
)

/**
 * Where a value came from and what it filled, carried from extraction to storage unchanged: the slot
 * key, the model's role word, how it was obtained, the model's own confidence (the value's
 * `confidence` is the final one), and the evidence with its page and position.
 */
@Serializable
data class FieldProvenance(
    val slotKey: String? = null,
    val role: String? = null,
    val origin: String? = null,
    val aiConfidence: Float? = null,
    val evidence: String? = null,
    val page: Int? = null,
    val bbox: TextBounds? = null,
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

/**
 * How much of a document [AiExtractionUseCase] actually gave the model, when it did not all
 * fit — see [DocumentUnderstanding.inputTruncation].
 *
 * [charactersRead]/[totalCharacters] are always exact — they are the same character budget
 * the use case truncated the labelled layout against. [pagesRead]/[totalPages] are a
 * best-effort estimate from page boundaries the caller supplied (`AiExtractionUseCase`'s
 * `pageBlockCounts`); null when those were not known, in which case a UI shows the character
 * figures instead of a page count.
 */
@Serializable
data class InputTruncation(
    val charactersRead: Int,
    val totalCharacters: Int,
    val pagesRead: Int? = null,
    val totalPages: Int? = null,
)

@Serializable
data class RecognisedFact(
    val label: String,
    val value: String,
    val kind: FactKind,
    val confidence: Float,
    val provenance: FieldProvenance? = null,
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

    /**
     * Set when the document's own layout had to be cut to fit the extraction budget (5.4) —
     * how much of the document the model actually *saw*, as opposed to [truncated], which is
     * about the model's *answer* being cut off mid-generation. A long, multi-page letter can
     * hit this while [truncated] stays false (the model finished its answer fine — it simply
     * was never shown the later pages), or the reverse.
     *
     * Never set by the model itself — [AiExtractionUseCase] computes and attaches this after
     * parsing, from the character budget it enforced before the model ever ran. Null means
     * the whole document fit inside the budget.
     */
    val inputTruncation: InputTruncation? = null,

    /**
     * At most eight words in the letter's language, sender and purpose. Written by the model, so
     * it is a label, not a fact. Empty when there was none.
     */
    val title: String = "",

    /** The model's own confidence in [documentType]; no check can raise or lower it. 0 when there was no reading. */
    val documentTypeConfidence: Float = 0f,

    /** One or two sentences: what the reader must know or do. Empty when there was none. */
    val summary: String = "",

    /** Questions a reader might ask, in the letter's language, for the chat to offer. */
    val suggestedQuestions: List<String> = emptyList(),

    /**
     * False when no model read the document and [facts] are only values found by code, without
     * roles or meaning. A caller must not link entities or trust the facts as it would a reading.
     */
    val modelUsed: Boolean = true,
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
