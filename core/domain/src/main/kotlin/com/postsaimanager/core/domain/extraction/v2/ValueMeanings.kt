package com.postsaimanager.core.domain.extraction.v2

/** What a value is a date or an amount: the two kinds of value whose meaning is asked. */
enum class MeaningKind {
    DATE,
    AMOUNT,
    ;

    companion object {
        /** The kind of meaning a slot's value can have, or null for a slot that holds neither a date nor an amount. */
        fun of(kind: SlotKind): MeaningKind? = when (kind) {
            SlotKind.DATE, SlotKind.DEADLINE -> DATE
            SlotKind.AMOUNT -> AMOUNT
            else -> null
        }
    }
}

/**
 * One thing a date or an amount can mean in a document.
 *
 * @property id the stable key stored with the value (see [ValueMeanings.role]); the UI renders its words from a string resource per id
 * @property kind whether it is a meaning of a date or of an amount
 * @property description one English phrase saying what the value is, a content description for the scoring question
 *   ("Is «30.11.2026» ... <description>?") and for the chat's grounding; a model prompt, not UI text
 * @property exclusive whether a document has only one value with this meaning (the amount to pay, the date of the letter): a reader that
 *   gives it to two values has made one of them wrong, and the first it listed keeps it ([com.postsaimanager.core.domain.extraction.gemma.GemmaReadingVerifier])
 */
data class ValueMeaning(val id: String, val kind: MeaningKind, val description: String, val exclusive: Boolean = false)

/**
 * What a date or an amount of a document can mean, as data: a short list per kind. The reading scores each date and each amount it
 * keeps against every meaning of its kind, each over the content-free baseline with a margin ([com.postsaimanager.core.domain.extraction.zones.ScoringProfile.meaningMargin]);
 * a value no meaning beats it by is "other", which is no entry here and is stored as no meaning at all. A new meaning is one line
 * here, one string per language in the UI, and nothing else.
 *
 * Independent of the document's type: every document is asked the same list.
 */
class ValueMeanings(val all: List<ValueMeaning>) {

    init {
        require(all.map { it.id }.toSet().size == all.size) { "duplicate meaning id" }
    }

    /** The meanings of [kind], in the order they are scored. */
    fun of(kind: MeaningKind): List<ValueMeaning> = all.filter { it.kind == kind }

    fun byId(id: String?): ValueMeaning? = all.firstOrNull { it.id == id?.trim() }

    companion object {
        /** What a stored role starts with when it is a decided meaning, so a meaning is never mistaken for a slot's own expected role. */
        const val ROLE_PREFIX = "meaning:"

        val DEFAULT = ValueMeanings(
            listOf(
                ValueMeaning("DUE_DATE", MeaningKind.DATE, "the date by which the reader must pay"),
                ValueMeaning("APPOINTMENT", MeaningKind.DATE, "the date of an appointment or a meeting the reader is to attend"),
                ValueMeaning("DEADLINE", MeaningKind.DATE, "the last date on which the reader can cancel, object or reply"),
                ValueMeaning("PERIOD_START", MeaningKind.DATE, "the first day of a period this document covers"),
                ValueMeaning("PERIOD_END", MeaningKind.DATE, "the last day of a period this document covers"),
                ValueMeaning("LETTER_DATE", MeaningKind.DATE, "the date on which this document was written or issued", exclusive = true),
                ValueMeaning("BIRTH_DATE", MeaningKind.DATE, "a person's date of birth"),
                ValueMeaning("TOTAL_DUE", MeaningKind.AMOUNT, "the amount the reader has to pay", exclusive = true),
                ValueMeaning("CREDIT", MeaningKind.AMOUNT, "an amount credited or refunded to the reader"),
                ValueMeaning("PREMIUM", MeaningKind.AMOUNT, "an insurance premium or a regular contribution"),
                ValueMeaning("INVOICE_TOTAL", MeaningKind.AMOUNT, "the total of an invoice", exclusive = true),
                ValueMeaning("FEE", MeaningKind.AMOUNT, "a fee or a surcharge"),
            ),
        )

        /** The role stored for a value whose decided meaning is [meaning] (`ExtractedData.role`, `FieldProvenance.role`). */
        fun role(meaning: ValueMeaning): String = ROLE_PREFIX + meaning.id

        /** The meaning a stored [role] holds, or null when it is no meaning (a party role, a slot's expected role, nothing). */
        fun fromRole(role: String?, registry: ValueMeanings = DEFAULT): ValueMeaning? =
            role?.takeIf { it.startsWith(ROLE_PREFIX) }?.removePrefix(ROLE_PREFIX)?.let(registry::byId)
    }
}
