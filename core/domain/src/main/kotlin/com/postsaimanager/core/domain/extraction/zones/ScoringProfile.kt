package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.SlotKey

/**
 * How a log-odds score becomes a decision, as data: the abstain threshold per question (the best candidate
 * is taken only when its score is above it, otherwise the answer is NONE) and how far above it counts as
 * MEDIUM or HIGH confidence. Tuned on benchmark recordings, per model (see [ModelProfile]).
 */
data class ScoringProfile(
    val defaultThreshold: Double = 0.0,
    /** By question name (`sender`, `addressee`, `slot:total`, ...). */
    val thresholds: Map<String, Double> = emptyMap(),
    val mediumMargin: Double = 1.0,
    val highMargin: Double = 3.0,
) {
    fun threshold(ask: String): Double = thresholds[ask] ?: defaultThreshold

    fun confidence(margin: Double): String = when {
        margin >= highMargin -> "HIGH"
        margin >= mediumMargin -> "MEDIUM"
        else -> "LOW"
    }
}

/**
 * What each question is called in a scoring question ("Is «X» <what>?"). Data: a party role or a slot has
 * a statement, written by what it does in a letter, in English. A slot with no statement of its own is
 * described by its label.
 */
object ScoringDescriptions {

    private val ROLES = mapOf(
        QuestionNames.SENDER to "the sender: the party that wrote and sent this letter",
        QuestionNames.ADDRESSEE to "the addressee: the party this letter is addressed to",
        QuestionNames.CONTACT to "a contact person named as the one who handles the matter",
        QuestionNames.CARE_OF to "a party in whose care the letter is sent (a mailbox for the addressee)",
        QuestionNames.SUBJECT_PERSON to "a person the letter is about, other than the addressee",
    )

    private val SLOTS = mapOf(
        "letter_date" to "the date of the letter itself (when it was written or issued)",
        "total" to "the main amount of this document: what the reader has to pay, or the total",
        "due_date" to "the date by which the reader must pay or act",
        "iban" to "the IBAN of the account the reader should pay to",
        "reference" to "a reference the letter cites for this matter (a file, case, transaction or payment reference)",
        "customer_no" to "the number that identifies the reader as a customer, member or account holder",
    )

    fun ofRole(name: String): String = ROLES[name] ?: "a party of this letter"

    fun ofSlot(slot: SlotKey): String = SLOTS[slot.json] ?: "the ${slot.label.lowercase()}"

    /** The kind statements of a party, in [com.postsaimanager.core.domain.extraction.v2.StructuredGrammar.PARTY_KINDS] order (OTHER is never chosen). */
    val KINDS: List<Pair<String, String>> = listOf(
        "PERSON" to "the name of a private person",
        "AUTHORITY" to "the name of an authority or public body",
        "COMPANY" to "the name of a company or other organisation",
    )

    const val HOUSEHOLD = "the name of a family or household (several people living together)"
}
