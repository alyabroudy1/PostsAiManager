package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionPart
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState

/**
 * The reading pipeline's own answers about one letter, as a few lines for the chat grounding: what the letter asks of its reader
 * (the action items already decided), with the date, amount, sender and reference each one states and what that date MEANS
 * ("the date by which the reader is asked to pay"), and any deadline field no action covers.
 *
 * They are the AI's earlier answers, given as context so that "the due date" in a chat means the deadline the reading found, not
 * the most prominent date on the page (a contract start). No rule picks a date for the model here, and nothing is written for a
 * letter that has no action and no deadline. Values are the live stored ones, so a correction by the person is what the model sees.
 *
 * Pure.
 */
object LetterReadingContext {

    /** The section, starting with a blank line, or an empty string when the reading found nothing to state. */
    fun section(actionItems: List<ActionItem>, fields: List<ExtractedData>): String {
        val live = fields.filter { !it.deletedByUser && it.reviewState != ReviewState.IGNORED && it.fieldValue.isNotBlank() }
        fun bound(item: ActionItem, part: ActionPart): ExtractedData? =
            item.bindings[part.key]?.let { key -> live.firstOrNull { it.slotKey == key } }

        val lines = mutableListOf<String>()
        val coveredDates = mutableSetOf<String>()
        actionItems.forEach { item ->
            val kind = ActionKinds.of(item.kind) ?: return@forEach
            val date = bound(item, ActionPart.DATE)
            val amount = bound(item, ActionPart.AMOUNT)
            val party = bound(item, ActionPart.PARTY)
            val reference = bound(item, ActionPart.REFERENCE)
            date?.let { d -> d.slotKey?.let(coveredDates::add) }
            val parts = listOfNotNull(
                date?.let { "date ${it.fieldValue.trim()}" + (kind.dateMeaning?.let { m -> " ($m)" } ?: "") },
                amount?.let { "amount ${it.fieldValue.trim()}" + (kind.amountMeaning?.let { m -> " ($m)" } ?: "") },
                party?.let { "sender ${it.fieldValue.trim()}" },
                reference?.let { "reference ${it.fieldValue.trim()}" },
            )
            lines += "- The reader is asked to ${kind.task}" + if (parts.isEmpty()) "" else ": " + parts.joinToString("; ")
        }
        live.filter { it.fieldType == ExtractedFieldType.DEADLINE && it.slotKey !in coveredDates }
            .forEach { lines += "- ${it.fieldName}: ${it.fieldValue.trim()}" }

        if (lines.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## What was read from this letter")
            appendLine("The app already read this letter; these are its answers.")
            lines.take(MAX_LINES).forEach { appendLine(it) }
        }
    }

    private const val MAX_LINES = 6
}
