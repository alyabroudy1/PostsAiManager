package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession

/**
 * Writes the specific name of a document: a short line in the document's own language that says what it is and whom or what it concerns
 * (the title uses it). One generation, bounded by [DocumentNameFormat.grammar] and [DocumentNameFormat.MAX_TOKENS], on the session the reading
 * already opened (the letter is the prefix, so nothing is read twice). The prompt shows what was read, and the category the reading decided or
 * the person gave, so the name fits both; [DocumentNameVerifier] then keeps it only if it claims no fact the letter does not give.
 *
 * A failed or ungrounded answer means no name: the title then falls back to what it was built from before (the printed subject line).
 */
class DocumentNameWriter(
    private val session: PromptSession,
    private val verifier: DocumentNameVerifier = DocumentNameVerifier(),
) {

    /**
     * @param read what the reading found ([ReadFacts.block]), shown to the model; may be empty
     * @param context the category as one sentence ("The user says this document is a bill or an invoice."), or empty when none is known
     * @param ocrText the letter's text, the grounding reference
     * @param languageCode the document's language as a BCP-47 code, or null when unknown
     * @return the name, or null when the model wrote none or it is not grounded in the letter
     */
    suspend fun write(read: String, context: String, ocrText: String, languageCode: String?): String? {
        val answer = (session.ask(prompt(read, context, languageCode), DocumentNameFormat.grammar(), DocumentNameFormat.MAX_TOKENS) as? PamResult.Success)?.data
            ?: return null
        return verifier.verify(answer, ocrText)
    }

    internal fun prompt(read: String, context: String, languageCode: String?): String = buildString {
        if (read.isNotBlank()) append(read).append('\n')
        if (context.isNotBlank()) append(context).append('\n')
        append("QUESTION: Write a short name for THIS document: what kind of document it is and whom or what it concerns, in at most ")
        append(DocumentNameFormat.MAX_CHARS).append(" characters. ")
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "Write it in the language with the code \"$it\". " } ?: "Write it in the document's own language. ")
        append("Use only names, words and numbers that are printed in the document; do not invent any.")
        append("\nANSWER FORMAT: the name only, on one line")
    }
}
