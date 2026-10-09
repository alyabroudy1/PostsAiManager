package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState

/**
 * One action ready to be shown: its kind, the values its sentence states, and the stored fields behind them.
 *
 * @property date the date the sentence states; null when none is bound or the bound value is not a readable date
 * @property amount the amount as stored, or null
 * @property party the sender's name as stored, or null
 * @property rows the fields behind the sentence (date, amount, account, reference), for their inline Confirm and Edit; never the party,
 *   which has its own place on the tab
 * @property valueRows the fields shown under the sentence as a value to copy (the account, a reference, a date that is words not a date)
 * @property item the stored action this line is rendered from: what an edit or a deletion of the line is addressed to
 * @property text the person's own wording, shown instead of the sentence rendered from [kind]; null for a model action
 */
class ActionLine(
    val kind: ActionKind,
    val date: ActionDate?,
    val amount: String?,
    val party: String?,
    val rows: List<ExtractedData>,
    val valueRows: List<ExtractedData>,
    val offer: ContactOffer? = null,
    val item: ActionItem = ActionItem(kind.id),
    val text: String? = null,
)

/**
 * The contact person's phone and e-mail, offered under an action of the kind "contact the sender" when the letter itself gives none. The
 * action was chosen by the reading; this only supplies the values the app holds for the person to reach them.
 */
data class ContactOffer(val name: String, val phone: String?, val email: String?) {
    val isEmpty: Boolean get() = phone == null && email == null
}

/**
 * Resolves the stored [ActionItem]s against the document's fields as they are NOW: the line is rendered from live values, so a value a
 * person corrected is the value the line states, and a field a person ignored drops out of it (a shorter line). There is no stale line
 * to detect: nothing was ever written down.
 *
 * Pure.
 */
object ActionLines {

    /**
     * @param contact the contact person to offer (the letter's, else the organisation's current one); it is offered under a "contact"
     *   action only for the channel the letter has no value of its own for (no live phone field, no live e-mail field)
     */
    fun resolve(items: List<ActionItem>, fields: List<ExtractedData>, contact: ContactPerson? = null): List<ActionLine> {
        val live = fields.filter { !it.deletedByUser && it.reviewState != ReviewState.IGNORED && it.fieldValue.isNotBlank() }
        val letterHasPhone = live.any { it.fieldType == ExtractedFieldType.PHONE }
        val letterHasEmail = live.any { it.fieldType == ExtractedFieldType.EMAIL }
        val offer = contact?.let {
            ContactOffer(it.name, it.phone?.takeIf { p -> p.isNotBlank() && !letterHasPhone }, it.email?.takeIf { e -> e.isNotBlank() && !letterHasEmail })
        }?.takeUnless { it.isEmpty }
        // The person's chosen meaning of a date or an amount first, then the slot the reading bound the part to.
        fun field(item: ActionItem, part: ActionPart): ExtractedData? = ActionBinding.field(item, part, live)

        return items.filter { !it.removed }.mapNotNull { item ->
            val kind = ActionKinds.of(item.kind) ?: return@mapNotNull null
            val dateRow = field(item, ActionPart.DATE)
            // A date the person gave the action wins over the bound field's.
            val date = item.dueDate?.let(ActionDates::read) ?: dateRow?.let { ActionDates.read(it.fieldValue) }
            val amountRow = field(item, ActionPart.AMOUNT)
            val ibanRow = field(item, ActionPart.IBAN)
            val referenceRow = field(item, ActionPart.REFERENCE)
            val shownUnder = listOfNotNull(
                dateRow.takeIf { date == null && item.dueDate == null },
                ibanRow,
                referenceRow,
            )
            ActionLine(
                kind = kind,
                date = date,
                amount = amountRow?.fieldValue?.trim(),
                party = field(item, ActionPart.PARTY)?.fieldValue?.trim(),
                rows = listOfNotNull(dateRow, amountRow, ibanRow, referenceRow),
                valueRows = shownUnder,
                offer = offer.takeIf { kind.id == ActionKinds.CONTACT.id },
                item = item,
                text = item.text?.takeIf { it.isNotBlank() },
            )
        }
    }
}
