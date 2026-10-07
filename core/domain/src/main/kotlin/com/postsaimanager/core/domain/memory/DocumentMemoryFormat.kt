package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.model.DocumentNote

/**
 * What the assistant reads of a document's notes: the "document memory" slot of the chat context. Pure.
 *
 * The slot is small and stable on purpose (every character is read by the model before the first message): at most [MAX_NOTES] notes
 * and [MAX_CHARS] characters, pinned notes first, then the most recently written or edited. A note that does not fit ends the list
 * (older notes never jump ahead of a newer one); a single note longer than the cap is cut.
 *
 * ```
 * - Reminder set for 8 Oct 09:00: Send the documents for the Bürgergeld application
 * - Already paid on 5 Oct, says the user
 * ```
 */
object DocumentMemoryFormat {

    const val MAX_NOTES = 10
    const val MAX_CHARS = 800

    private const val BULLET = "- "

    /**
     * The slot's text, one note per line, or an empty string when there is nothing to remember.
     *
     * @param labelOf who a note is about, shown before it ("- Maria: ..."): the all-documents chat's notes belong to household
     *   persons. Null (the default, and every document note) shows the note alone.
     */
    fun format(notes: List<DocumentNote>, labelOf: (DocumentNote) -> String? = { null }): String {
        val lines = ArrayList<String>()
        var used = 0
        val ordered = notes.sortedWith(compareByDescending<DocumentNote> { it.pinned }.thenByDescending { it.updatedAt })
        for (note in ordered) {
            if (lines.size >= MAX_NOTES) break
            val body = note.text.replace(WHITESPACE, " ").trim()
            if (body.isEmpty()) continue
            val label = labelOf(note)?.trim()?.takeIf { it.isNotEmpty() }
            val line = BULLET + (label?.let { "$it: " }.orEmpty()) + body
            val cost = line.length + if (lines.isEmpty()) 0 else 1
            if (used + cost > MAX_CHARS) {
                if (lines.isEmpty()) lines += line.take(MAX_CHARS - 1) + "…"
                break
            }
            lines += line
            used += cost
        }
        return lines.joinToString("\n")
    }

    private val WHITESPACE = Regex("\\s+")
}
