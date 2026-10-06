package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ExtractedData
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
 */
class ActionLine(
    val kind: ActionKind,
    val date: ActionDate?,
    val amount: String?,
    val party: String?,
    val rows: List<ExtractedData>,
    val valueRows: List<ExtractedData>,
)

/**
 * Resolves the stored [ActionItem]s against the document's fields as they are NOW: the line is rendered from live values, so a value a
 * person corrected is the value the line states, and a field a person ignored drops out of it (a shorter line). There is no stale line
 * to detect: nothing was ever written down.
 *
 * Pure.
 */
object ActionLines {

    fun resolve(items: List<ActionItem>, fields: List<ExtractedData>): List<ActionLine> {
        val live = fields.filter { !it.deletedByUser && it.reviewState != ReviewState.IGNORED && it.fieldValue.isNotBlank() }
        fun field(item: ActionItem, part: ActionPart): ExtractedData? = item.bindings[part.key]?.let { key -> live.firstOrNull { it.slotKey == key } }

        return items.mapNotNull { item ->
            val kind = ActionKinds.of(item.kind) ?: return@mapNotNull null
            val dateRow = field(item, ActionPart.DATE)
            val date = dateRow?.let { ActionDates.read(it.fieldValue) }
            val amountRow = field(item, ActionPart.AMOUNT)
            val ibanRow = field(item, ActionPart.IBAN)
            val referenceRow = field(item, ActionPart.REFERENCE)
            val shownUnder = listOfNotNull(
                dateRow.takeIf { date == null },
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
            )
        }
    }
}
