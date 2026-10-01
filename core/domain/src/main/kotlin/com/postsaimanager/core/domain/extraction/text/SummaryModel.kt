package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.model.SummarySource

/**
 * A summary that always exists.
 *
 * - [SummarySource.MODEL]: [text] is the model's sentences, [code] is null.
 * - [SummarySource.TEMPLATE]: [text] is null and [code] is [SummaryWriter.TEMPLATE_CODE]; [args] are the verified fields
 *   in the order [SummaryFacts.templateArgs] documents, and the UI renders the localised sentence from them.
 *
 * A writer never produces [SummarySource.USER]; that is a person's own edit.
 */
data class SummaryResult(val text: String?, val origin: SummarySource, val code: String?, val args: List<String>)

/**
 * The verified fields a summary may rest on. Every value is a verified string already (a quote-checked name, a
 * normalised amount or date); a null is a field the letter did not give. Machine ids in the prompt, never prose.
 */
data class SummaryFacts(
    val familyId: String,
    val sender: String? = null,
    val addressee: String? = null,
    val amount: String? = null,
    val dueDate: String? = null,
    val date: String? = null,
    val subject: String? = null,
    val reference: String? = null,
) {

    /** The non-blank facts as (role, value), in a fixed order. */
    fun entries(): List<Pair<String, String>> = listOf(
        SENDER to sender,
        ADDRESSED_TO to addressee,
        AMOUNT to amount,
        DUE_DATE to dueDate,
        DATE to date,
        SUBJECT to subject,
        REFERENCE to reference,
    ).mapNotNull { (role, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { role to it } }

    /**
     * The facts a reading's first stage hands to its second (`EnrichmentTicket.facts`): every entry but the subject, which the second
     * stage reads itself. The inverse of [of].
     */
    fun carried(): Map<String, String> = entries().filter { it.first != SUBJECT }.toMap()

    /** Every value a summary may quote: the texts a number or a name in the answer is checked against. */
    fun values(): List<String> = entries().map { it.second }

    /**
     * The args of the template summary, six positions, `""` for a missing field:
     * family id, sender, addressee, amount, due date, subject.
     */
    fun templateArgs(): List<String> = listOf(
        familyId.trim(), sender.orEmpty().trim(), addressee.orEmpty().trim(), amount.orEmpty().trim(),
        dueDate.orEmpty().trim(), subject.orEmpty().trim(),
    )

    companion object {
        /** The roles of [entries], as the prompt names them and as a ticket carries them. */
        const val SENDER = "sender"
        const val ADDRESSED_TO = "addressed_to"
        const val AMOUNT = "amount"
        const val DUE_DATE = "due_date"
        const val DATE = "date"
        const val SUBJECT = "subject"
        const val REFERENCE = "reference"

        /** The facts of a family from the [carried] roles of a ticket plus the [subject] the second stage verified. */
        fun of(familyId: String, carried: Map<String, String>, subject: String?): SummaryFacts = SummaryFacts(
            familyId = familyId, sender = carried[SENDER], addressee = carried[ADDRESSED_TO], amount = carried[AMOUNT],
            dueDate = carried[DUE_DATE], date = carried[DATE], subject = subject, reference = carried[REFERENCE],
        )
    }
}
