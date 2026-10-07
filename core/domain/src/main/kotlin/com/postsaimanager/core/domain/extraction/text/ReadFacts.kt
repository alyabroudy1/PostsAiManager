package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.model.TicketSlot

/**
 * What the reading found, as the lines that go in front of the questions asked AFTER it (the document's category, its specific name): the
 * sender and the recipient, then every stored date and amount with what the reading decided it means ("Deadline: 30.11.2026 (the date by which
 * the reader must pay)"). The model then judges a document it has already understood instead of one it has only skimmed.
 *
 * Every value here is one the first stage verified (a name found in the letter, a value the extractor found), as the ticket carries it; this
 * only lays them out. Pure, and the lines are English labels around the letter's own values (a prompt, not UI text).
 */
object ReadFacts {

    private const val HEADER = "WHAT WAS READ FROM THIS DOCUMENT (who it is from and for, its dates and amounts and what each one means):"

    /** The line that ends the block, so a recording of the prompt can be compared without it ([strip]). */
    const val END = "(end of what was read)"

    /** The most lines one block holds: the questions it precedes are asked several times, so it stays short. */
    const val MAX_LINES = 12

    /**
     * @param facts the first stage's carried facts (`EnrichmentTicket.facts`: the sender, who it is addressed to, ...), by role
     * @param slots the stored slot values, each with the meaning the reading gave it, if it did
     * @return the block, ending with a line break, or "" when nothing was read (the questions then stand alone)
     */
    fun block(facts: Map<String, String>, slots: List<TicketSlot>): String {
        val lines = ArrayList<String>()
        facts[SummaryFacts.SENDER]?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "- sender: $it" }
        facts[SummaryFacts.ADDRESSED_TO]?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "- addressed to: $it" }
        for (s in slots) {
            val value = s.value.trim().replace('\n', ' ')
            if (value.isEmpty()) continue
            lines += "- ${s.label}: $value" + (s.meaning?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        }
        if (lines.isEmpty()) return ""
        return buildString {
            append(HEADER).append('\n')
            lines.take(MAX_LINES).forEach { append(it).append('\n') }
            append(END).append('\n')
        }
    }

    /** [text] without a leading block: what a recording of a question holds, which was made before the block was put in front of it. */
    fun strip(text: String): String {
        val at = text.indexOf("$END\n")
        return if (at < 0) text else text.substring(at + END.length + 1)
    }
}
