package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ChatImageStore
import com.postsaimanager.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * The Gallery's "reset session" for a chat: the conversation of a document starts over.
 *
 * The visible history is deleted, not hidden: the conversation (and, by the database's own cascade, its messages and their
 * citations) is removed, the pictures attached to it go with it, and the model's conversation is dropped, so the next message
 * opens a fresh one over the same grounding (the engine primes it again). Nothing is archived, so a chat the user cleared stays
 * cleared, and no database change is needed: the conversation row is made again, lazily, by the next message
 * ([SendChatMessageUseCase]).
 *
 * The caller stops a reply in flight first; this does not.
 */
class StartNewChatUseCase @Inject constructor(
    private val conversations: ConversationRepository,
    private val engine: ChatEngine,
    private val images: ChatImageStore,
    /** The chat's session is forgotten without an end event: its notes would be about a conversation that is gone. */
    private val sessions: ChatSessionTracker = ChatSessionTracker(),
) {

    /**
     * @param conversationId the chat to clear.
     * @return true when the history is gone; false when it could not be deleted (nothing else is touched then).
     */
    suspend operator fun invoke(conversationId: String): Boolean {
        // A conversation that was never started has no row to delete, which is already "new".
        if (conversations.deleteConversation(conversationId) is PamResult.Error) return false
        images.deleteAll(conversationId)
        engine.resetChatSession()
        // The next message begins a session over an empty transcript: the card alone, no tail.
        sessions.discard(conversationId)
        return true
    }
}
