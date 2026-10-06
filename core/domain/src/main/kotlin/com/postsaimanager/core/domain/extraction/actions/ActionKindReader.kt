package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.domain.extraction.v2.Canonical
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.TicketSlot
import java.util.Locale

/**
 * Scores a batch of yes/no questions in the letter's session and gives their log-odds in order, or null when the engine failed. The
 * reading's own scorer (it records and counts every batch like any other) is what is passed in: this is not a second scorer.
 */
fun interface ActionScorer {
    suspend fun score(name: String, questions: List<String>): List<Double>?
}

/**
 * Chooses what the reader has to do, by score, from a catalogue of kinds ([ActionKinds]); the model never writes a line.
 *
 * 1. One batch scores "does the letter ask the reader to do anything?" and every kind ([ActionQuestions]); [ActionKindSelector] chooses.
 * 2. For each chosen kind, the stored dates (and, for a kind that states one, the stored amounts) are scored as "is this value the kind's
 *    date (amount)?" and the best one above its threshold is bound. Only stored, verified values are ever offered.
 * 3. The party is the stored sender (a payment's payee is the sender, never the addressee); a reference to quote and the account are the
 *    stored slots the kind names.
 *
 * A part with nothing to bind is left out (the line is rendered shorter). Code only verifies: nothing is free text.
 */
class ActionKindReader(
    private val scorer: ActionScorer,
    private val profile: ActionKindProfile = ActionKindProfile(),
    private val kinds: List<ActionKind> = ActionKinds.ALL,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    /** For the reading's trace: ids and scores only, never a word of the letter. */
    private val trace: (String) -> Unit = {},
) {

    /** What one reading decided, with the scores it rests on (for the trace and for fitting). */
    class Reading(val items: List<ActionItem>, val any: Double, val kindScores: Map<String, Double>, val chosen: List<String>)

    private class Ask(val kind: ActionKind, val part: ActionPart, val slot: TicketSlot, val question: String)

    /**
     * @param slots the stored slot values the reading holds (key, label, value)
     * @param senderKnown whether a sender is stored, so a kind may name it
     * @return the actions (empty when the letter asks nothing), or null when the kinds could not be scored: a stored list then stays
     */
    suspend fun read(slots: List<TicketSlot>, senderKnown: Boolean): Reading? {
        val first = scorer.score(KINDS_BATCH, listOf(ActionQuestions.anything()) + kinds.map(ActionQuestions::kind))
            ?.takeIf { it.size == kinds.size + 1 } ?: return null
        val any = first.first()
        val kindScores = kinds.zip(first.drop(1))
        val chosen = ActionKindSelector.choose(any, kindScores, profile)
        trace(
            String.format(Locale.ROOT, "actions any=%+.2f kinds=[%s] chosen=%s", any, kindScores.joinToString(" ") { (k, s) -> k.id + String.format(Locale.ROOT, "=%+.2f", s) }, chosen.joinToString(",") { it.kind.id }),
        )
        if (chosen.isEmpty() && !profile.scoreEveryBinding) return Reading(emptyList(), any, kindScores.associate { it.first.id to it.second }, emptyList())

        val asks = bindingAsks(if (profile.scoreEveryBinding) kinds else chosen.map { it.kind }, slots)
        val scores = if (asks.isEmpty()) emptyList() else scorer.score(BINDINGS_BATCH, asks.map { it.question })?.takeIf { it.size == asks.size }
        // A failed binding batch leaves the chosen kinds with nothing scored to bind: shorter lines, never an unverified value.
        val bound = scores?.let { asks.zip(it) }.orEmpty()

        val items = chosen.map { c ->
            val bindings = LinkedHashMap<String, String>()
            for (part in listOf(ActionPart.DATE, ActionPart.AMOUNT)) {
                val threshold = if (part == ActionPart.DATE) profile.dateThreshold else profile.amountThreshold
                bound.filter { (ask, _) -> ask.kind == c.kind && ask.part == part && ask.slot.value.isNotBlank() }
                    .maxByOrNull { it.second }?.takeIf { it.second > threshold }?.let { bindings[part.key] = it.first.slot.key }
            }
            if (c.kind.party && senderKnown) bindings[ActionPart.PARTY.key] = SENDER_KEY
            c.kind.referenceSlots.firstOrNull { key -> slots.any { it.key == key && it.value.isNotBlank() } }?.let { bindings[ActionPart.REFERENCE.key] = it }
            if (c.kind.iban && slots.any { it.key == IBAN_KEY && it.value.isNotBlank() }) bindings[ActionPart.IBAN.key] = IBAN_KEY
            ActionItem(c.kind.id, bindings)
        }
        trace("actions bound=[" + items.joinToString(" ") { i -> i.kind + i.bindings.entries.joinToString(",", "(", ")") { "${it.key}:${it.value}" } } + "]")
        return Reading(items, any, kindScores.associate { it.first.id to it.second }, chosen.map { it.kind.id })
    }

    /** The questions about the stored dates and amounts under each kind in [scope] that states them. */
    private fun bindingAsks(scope: List<ActionKind>, slots: List<TicketSlot>): List<Ask> {
        val dates = slots.filter { it.value.isNotBlank() && kindOf(it) == SlotKind.DATE && !isDocumentDate(it) }
        val amounts = slots.filter { it.value.isNotBlank() && kindOf(it) == SlotKind.AMOUNT }
        return scope.flatMap { kind ->
            (if (kind.dateMeaning != null) dates.map { Ask(kind, ActionPart.DATE, it, ActionQuestions.date(kind, it)) } else emptyList()) +
                (if (kind.amountMeaning != null) amounts.map { Ask(kind, ActionPart.AMOUNT, it, ActionQuestions.amount(kind, it)) } else emptyList())
        }
    }

    /** A stored slot's kind as the schema declares it; a deadline is a date. */
    private fun kindOf(slot: TicketSlot): SlotKind? = schema.allSlots.firstOrNull { it.json == slot.key }?.kind?.let { if (it == SlotKind.DEADLINE) SlotKind.DATE else it }

    /** The date of the letter itself (or of its payment) is never the date of an action. */
    private fun isDocumentDate(slot: TicketSlot): Boolean = schema.allSlots.firstOrNull { it.json == slot.key }?.canonical == Canonical.DOCUMENT_DATE

    companion object {
        const val KINDS_BATCH = "action:kinds"
        const val BINDINGS_BATCH = "action:bindings"

        /** The stored slots of the sender and of the account: what a party or account binding refers to. */
        private val SENDER_KEY = UnderstandingToFields.SLOT_SENDER
        private val IBAN_KEY = Slots.IBAN.json
    }
}
