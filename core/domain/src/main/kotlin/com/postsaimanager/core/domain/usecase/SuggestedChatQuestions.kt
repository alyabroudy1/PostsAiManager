package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.ExtractedData

/**
 * Starter questions offered when a conversation has no messages yet (5.2).
 *
 * Pure and deterministic on purpose — the whole point is that it is unit-testable without a
 * ViewModel, a database, or a model call. `ChatViewModel` is the only caller: standalone chat
 * (`documentId == null`) uses [forStandaloneChat] once; document chat re-derives
 * [forDocument] whenever the document's extracted fields change, since a document opened
 * right after scanning has none yet and gains them as extraction finishes.
 *
 * ### Why these specific questions
 *
 * Every suggestion here is something *this app's own data* can actually answer — the
 * standalone ones read across scanned letters (deadlines, amounts owed, the latest letter);
 * the document ones read the fields [UnderstandingToFields] already extracts for that one
 * document. Earlier copy ("Show recent deadlines", "Summarize my unread mail", "Help me
 * organize my documents") described a generic inbox assistant this app is not — there is no
 * mail account connected, so "unread mail" or a mailbox to "organize" has nothing behind it.
 */
object SuggestedChatQuestions {

    /** Standalone chat (4.2, "Ask about your documents") — always exactly these three. */
    fun forStandaloneChat(): List<String> = listOf(
        "Which letters have a deadline coming up?",
        "What do I owe and to whom?",
        "Summarise my latest letter",
    )

    /**
     * Document chat — 3 to [MAX_SUGGESTIONS] questions, chosen from what was actually
     * extracted for this document.
     *
     * [DEADLINE_QUESTION]/[AMOUNT_QUESTION] appear only when that field's canonical slot
     * ([UnderstandingToFields.DEADLINE]/[UnderstandingToFields.AMOUNT]) is present — asking
     * "when is the deadline" of a letter with no deadline would just produce a made-up one.
     * [SUMMARISE_QUESTION] and [NEXT_STEPS_QUESTION] always appear: every scanned letter can
     * be summarised and "what should I do" is always a fair question to ask about one. A
     * document with neither special field yet (freshly scanned, extraction still running, or
     * genuinely without one) falls back to [SENDER_QUESTION] so there are still at least
     * [MIN_SUGGESTIONS] to show rather than a sparse two-item list.
     */
    fun forDocument(fields: List<ExtractedData>): List<String> {
        val fieldNames = fields.map { it.fieldName }.toSet()
        val suggestions = mutableListOf<String>()

        if (UnderstandingToFields.DEADLINE in fieldNames) suggestions += DEADLINE_QUESTION
        if (UnderstandingToFields.AMOUNT in fieldNames) suggestions += AMOUNT_QUESTION

        suggestions += SUMMARISE_QUESTION
        suggestions += NEXT_STEPS_QUESTION

        if (suggestions.size < MIN_SUGGESTIONS) suggestions += SENDER_QUESTION

        return suggestions.take(MAX_SUGGESTIONS)
    }

    const val DEADLINE_QUESTION = "When is the deadline and what happens if I miss it?"
    const val AMOUNT_QUESTION = "How much do I have to pay, and how?"
    const val SUMMARISE_QUESTION = "Summarise this letter in simple words"
    const val NEXT_STEPS_QUESTION = "What should I do next?"
    const val SENDER_QUESTION = "Who sent this letter, and what do they want?"

    private const val MIN_SUGGESTIONS = 3
    private const val MAX_SUGGESTIONS = 4
}
