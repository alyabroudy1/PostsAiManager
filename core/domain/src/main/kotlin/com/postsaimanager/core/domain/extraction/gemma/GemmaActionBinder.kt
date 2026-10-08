package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.actions.ActionPart
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.model.ActionItem

/**
 * Turns the actions the model named into the stored [ActionItem]s: a kind of the registry and the stored fields the action rests on.
 *
 * The model chose the kind and the date and amount it is for (as candidate ids); this finds the stored slot that holds each of
 * those values after the pipeline's verification, so a value the verifier dropped is never bound. The parts that are not a choice
 * follow the kind's own definition, as the scoring reading binds them: the party is the sender (never the addressee), the reference is
 * the first of the kind's reference slots the document holds, the account is the stored account when the kind shows one. A part with no
 * stored field is simply not stated.
 */
object GemmaActionBinder {

    private const val IBAN_SLOT = "iban"

    fun bind(actions: List<VerifiedAction>, result: ExtractionV2Result, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT): List<ActionItem> {
        val bySlot = result.slots.entries.filter { it.value.value.isNotBlank() }
        fun slotOf(candidateId: String?): String? = candidateId?.let { id -> bySlot.firstOrNull { it.value.candidateId == id }?.key?.json }
        val stored = bySlot.map { it.key.json }.toSet() + result.slotLists.filterValues { it.isNotEmpty() }.keys.map { it.json }

        return actions.mapNotNull { a ->
            val kind = vocab.actionKind(a.kind) ?: return@mapNotNull null
            val bindings = LinkedHashMap<String, String>()
            slotOf(a.dateCandidateId)?.takeIf { ActionPart.DATE in kind.parts }?.let { bindings[ActionPart.DATE.key] = it }
            slotOf(a.amountCandidateId)?.takeIf { ActionPart.AMOUNT in kind.parts }?.let { bindings[ActionPart.AMOUNT.key] = it }
            if (kind.party && result.parties.sender != null) bindings[ActionPart.PARTY.key] = ExtractionV2Adapter.SENDER_KEY
            kind.referenceSlots.firstOrNull { it in stored }?.let { bindings[ActionPart.REFERENCE.key] = it }
            if (kind.iban && IBAN_SLOT in stored) bindings[ActionPart.IBAN.key] = IBAN_SLOT
            ActionItem(kind.id, bindings)
        }
    }
}
