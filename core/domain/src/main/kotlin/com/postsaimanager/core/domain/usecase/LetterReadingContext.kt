package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionPart
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
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
    fun section(actionItems: List<ActionItem>, fields: List<ExtractedData>, contacts: LetterContacts = LetterContacts()): String {
        val lines = read(actionItems, fields).take(MAX_LINES)
        val contactLines = contactLines(contacts)
        if (lines.isEmpty() && contactLines.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## What was read from this letter")
            appendLine("The app already read this letter; these are its answers.")
            lines.forEach { appendLine(it.text) }
            contactLines.forEach { appendLine(it) }
        }
    }

    /**
     * The slot keys of the stored fields whose values [section] states, so that the grounding's own list of extracted details can
     * leave them out: the same date, amount, sender or reference would otherwise be in the prompt twice, and every character of the
     * prompt is read by the model before the first message.
     */
    fun statedSlots(actionItems: List<ActionItem>, fields: List<ExtractedData>): Set<String> =
        read(actionItems, fields).take(MAX_LINES).flatMap { it.slots }.toSet()

    /** One stated line and the slot keys of the fields whose values it carries. */
    private class Line(val text: String, val slots: Set<String>)

    private fun read(actionItems: List<ActionItem>, fields: List<ExtractedData>): List<Line> {
        val live = fields.filter { !it.deletedByUser && it.reviewState != ReviewState.IGNORED && it.fieldValue.isNotBlank() }
        fun bound(item: ActionItem, part: ActionPart): ExtractedData? =
            item.bindings[part.key]?.let { key -> live.firstOrNull { it.slotKey == key } }

        val lines = mutableListOf<Line>()
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
            lines += Line(
                "- The reader is asked to ${kind.task}" + if (parts.isEmpty()) "" else ": " + parts.joinToString("; "),
                listOfNotNull(date, amount, party, reference).mapNotNull { it.slotKey }.toSet(),
            )
        }
        live.filter { it.fieldType == ExtractedFieldType.DEADLINE && it.slotKey !in coveredDates }
            .forEach { lines += Line("- ${it.fieldName}: ${it.fieldValue.trim()}", listOfNotNull(it.slotKey).toSet()) }
        return lines
    }

    /**
     * The contact person the letter names and the organisation's current contact, as read facts with their phone and e-mail. They are
     * offered so a question such as "who is my contact there?" or a skill's recipient can be answered from them; nothing here decides
     * which contact the reader means.
     */
    private fun contactLines(contacts: LetterContacts): List<String> {
        val organisation = contacts.organisationName?.takeIf { it.isNotBlank() }
        val letterContact = contacts.letterContact
        val current = contacts.current
        return buildList {
            if (letterContact != null) add("- The contact person named in this letter: ${describe(letterContact)}")
            if (current != null) {
                val of = organisation?.let { " at $it" }.orEmpty()
                add(
                    if (current.id == letterContact?.id) "- ${current.name} is also the organisation's current contact$of"
                    else "- The current contact$of: ${describe(current)}",
                )
            }
        }
    }

    private fun describe(contact: ContactPerson): String = listOfNotNull(
        contact.name,
        contact.title?.takeIf { it.isNotBlank() },
        contact.phone?.takeIf { it.isNotBlank() }?.let { "phone $it" },
        contact.email?.takeIf { it.isNotBlank() }?.let { "email $it" },
    ).joinToString(", ")

    private const val MAX_LINES = 6
}
