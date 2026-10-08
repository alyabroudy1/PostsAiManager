package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings

/**
 * Whether what a date MEANS ([ValueMeaningReader]) contradicts the slot that holds it. Code only verifies: the model chose the value for
 * the slot and, separately, said what the value means; when the two disagree the slot is left empty (the binding is dropped), never given
 * another value by a rule.
 *
 * Which slots are bound is data, read from the slot itself: a date slot is bound to a meaning when the roles it expects
 * ([SlotKey.expects]) name meanings of the registry and it has no open "OTHER" role. The letter date expects "LETTER_DATE", the due date
 * "DUE_DATE" and "DEADLINE" (and the loose "EVENT" role, which is no meaning), an appointment "APPOINTMENT"; a slot that expects "OTHER"
 * (the contract end, a payment date) takes any date. A value with no meaning ("other", or meanings not scored) contradicts nothing.
 */
object MeaningVerdict {

    /** The meanings [slot] is bound to: the registry's meanings among its expected roles; empty for a slot that takes any date. */
    fun boundTo(slot: SlotKey, registry: ValueMeanings = ValueMeanings.DEFAULT): Set<String> {
        if (slot.kind != SlotKind.DATE && slot.kind != SlotKind.DEADLINE) return emptySet()
        if ("OTHER" in slot.expects) return emptySet()
        return registry.of(MeaningKind.DATE).map { it.id }.filter { it in slot.expects }.toSet()
    }

    /** Whether [meaning] (null: none decided) contradicts the value in [slot]: it is bound to meanings and the value means another one. */
    fun contradicts(slot: SlotKey, meaning: ValueMeaning?, registry: ValueMeanings = ValueMeanings.DEFAULT): Boolean {
        meaning ?: return false
        val bound = boundTo(slot, registry)
        return bound.isNotEmpty() && meaning.id !in bound
    }
}
