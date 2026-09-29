package com.postsaimanager.core.domain.usecase

/**
 * Builds the text [RetrieveChunksUseCase] is actually asked with for one turn (4.4).
 *
 * A short follow-up — "and when is it due?", "wann genau?" — carries almost no signal for
 * keyword or semantic search on its own: the noun phrase that would actually match a chunk
 * ("the appeal", "die Frist") lives in the *previous* turn, not this one. Prepending the
 * previous user message restores that context for retrieval, without changing anything else
 * about the turn — see [SendChatMessageUseCase]'s KDoc on "Retrieval-augmented grounding":
 * the label format injected passages are shown under, and the text persisted for the user's
 * turn, are untouched either way. Only the string handed to retrieval changes.
 */
object FollowUpRetrievalQuery {

    /**
     * @param text this turn's raw question.
     * @param previousUserText the previous user turn in the same conversation, if any —
     *   `null` for the first message, which by definition cannot be a follow-up.
     * @return [text] alone unless it looks like a short follow-up, in which case
     *   [previousUserText] is prepended to give retrieval something to match against.
     */
    fun build(text: String, previousUserText: String?): String {
        if (previousUserText.isNullOrBlank()) return text
        val trimmed = text.trim()
        val wordCount = trimmed.split(WHITESPACE_REGEX).count { it.isNotBlank() }
        val looksLikeFollowUp = trimmed.length < SHORT_CHAR_THRESHOLD || wordCount < SHORT_WORD_THRESHOLD
        return if (looksLikeFollowUp) "$previousUserText $text" else text
    }

    private val WHITESPACE_REGEX = Regex("\\s+")

    /** Below this many characters, a question is treated as too short to retrieve alone. */
    private const val SHORT_CHAR_THRESHOLD = 40

    /** Below this many words, a question is treated as too short to retrieve alone. */
    private const val SHORT_WORD_THRESHOLD = 6
}
