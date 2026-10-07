package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.text.DocumentNameFormat
import com.postsaimanager.core.domain.extraction.text.DocumentNameVerifier

/**
 * Writes the title of a letter's event: one line in the document's own language that says what happened and to whom or what, at most
 * [DocumentNameFormat.MAX_CHARS] characters. One generation on the session the reading already opened (the letter is the prefix), bounded
 * by the document name's grammar. Grounded exactly like the specific name of a document: [DocumentNameVerifier] keeps it only if every
 * number and every name in it is printed in the letter; code never words or edits it.
 *
 * A failed or ungrounded answer is no title: the event then carries the document's own title.
 */
class EventTitleWriter(
    private val session: PromptSession,
    private val verifier: DocumentNameVerifier = DocumentNameVerifier(),
) {

    /**
     * @param read what the reading found ([com.postsaimanager.core.domain.extraction.text.ReadFacts.block]); may be empty
     * @param kind the kind the reading decided the event is, shown to the model as what the letter does
     * @param ocrText the letter's text, the grounding reference
     * @param languageCode the document's language as a BCP-47 code, or null when unknown
     * @return the title, or null when the model wrote none or it is not grounded in the letter
     */
    suspend fun write(read: String, kind: EventKind, ocrText: String, languageCode: String?): String? {
        val answer = (session.ask(prompt(read, kind, languageCode), DocumentNameFormat.grammar(), DocumentNameFormat.MAX_TOKENS) as? PamResult.Success)?.data
            ?: return null
        return verifier.verify(answer, ocrText)
    }

    internal fun prompt(read: String, kind: EventKind, languageCode: String?): String = buildString {
        if (read.isNotBlank()) append(read).append('\n')
        kind.description?.let { append("This letter ").append(it).append(".\n") }
        append(MARKER).append(": what the letter reports and whom or what it concerns, in at most ")
        append(DocumentNameFormat.MAX_CHARS).append(" characters. ")
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write it in the language with the code \"$it\". " } ?: "Write it in the document's own language. ")
        append("Use only names, words and numbers that are printed in the document; do not invent any.")
        append("\nANSWER FORMAT: the title only, on one line")
    }

    companion object {
        /** What the prompt's question starts with, so a recording or a replay can tell it from the others. */
        const val MARKER = "QUESTION: Write a short title for the event this letter reports"
    }
}
