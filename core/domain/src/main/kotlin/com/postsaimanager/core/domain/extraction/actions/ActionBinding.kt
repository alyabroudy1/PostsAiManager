package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.domain.extraction.v2.ValueMeanings
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ValueSource

/**
 * Which stored value an action states for each of its parts, the one place that decides it.
 *
 * The reading binds a part to a slot key. A meaning the user chose for a date or an amount ([ValueMeanings], stored in the row's role by the
 * person) wins over that: a date the user set as "payment due date" is the due date of the pay action, an amount they set as "amount to
 * pay" is the amount the pay action states. Only a value the person owns counts here (the reading's own meanings are what the binding was
 * made from), and which meanings a kind's date or amount can be is data.
 */
object ActionBinding {

    private val DEADLINES = setOf("DEADLINE", "DUE_DATE")

    /** The meanings of a date that stand for the date of each kind, by kind id. */
    private val dateMeanings: Map<String, Set<String>> = mapOf(
        ActionKinds.PAY.id to setOf("DUE_DATE"),
        ActionKinds.ATTEND.id to setOf("APPOINTMENT"),
        ActionKinds.REPLY.id to DEADLINES,
        ActionKinds.OBJECT_CANCEL.id to DEADLINES,
        ActionKinds.SEND_DOCUMENTS.id to DEADLINES,
        ActionKinds.SIGN_RETURN.id to DEADLINES,
        ActionKinds.CONFIRM_RENEW.id to DEADLINES,
        ActionKinds.CONTACT.id to DEADLINES,
        ActionKinds.OTHER.id to DEADLINES,
    )

    /** The meanings of an amount that stand for the amount of each kind, by kind id. */
    private val amountMeanings: Map<String, Set<String>> = mapOf(
        ActionKinds.PAY.id to setOf("TOTAL_DUE", "INVOICE_TOTAL"),
    )

    /**
     * The value [part] of [item] states among [live] fields: the person's chosen meaning first, else the field the part is bound to.
     * Null when neither exists.
     */
    fun field(item: ActionItem, part: ActionPart, live: List<ExtractedData>): ExtractedData? {
        val wanted = when (part) {
            ActionPart.DATE -> dateMeanings[item.kind]
            ActionPart.AMOUNT -> amountMeanings[item.kind]
            else -> null
        }
        if (wanted != null) {
            live.firstOrNull { it.source == ValueSource.USER && ValueMeanings.fromRole(it.role)?.id in wanted }?.let { return it }
        }
        return item.bindings[part.key]?.let { key -> live.firstOrNull { it.slotKey == key } }
    }
}
