package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.ChatSessionEnded
import com.postsaimanager.core.domain.usecase.ChatSessionTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the notes of a chat session when it ends (plan 16, A2 wired to A1): listens to [ChatSessionTracker.ended] and hands the
 * session's messages to [WriteSessionNotesUseCase].
 *
 * It runs in an application-scoped coroutine ([start]), never in a ViewModel's: leaving the chat clears the ViewModel, and that is
 * exactly when a session ends. Only a chat of a document has notes; the all-documents chat has none yet (A3). A chat that was
 * deleted meanwhile has nothing to read. The use case itself skips when no chat model is loaded or the model is busy.
 *
 * ### Which messages are "the session's"
 * The tracker stamps the session with the time it began ([ChatSessionEnded.startedAt]); the session's messages are the stored ones
 * created at or after it, oldest first. Earlier messages belong to a previous visit and were already written about (the last
 * exchange of the previous visit is only the model's continuity tail, not something said now).
 *
 * The notes are written by a generation on the loaded model, which closes the live native conversation. That needs no repair here:
 * the session is over, so the next open or send begins a new one, and the conversation is built from the plan
 * ([BuildModelContextUseCase]: the card with the notes just written, plus the last exchange), not from the old committed turns.
 */
@Singleton
class SessionNotesCollector @Inject constructor(
    private val sessions: ChatSessionTracker,
    private val conversations: ConversationRepository,
    private val writeNotes: WriteSessionNotesUseCase,
) {

    private var job: Job? = null

    /** Starts listening in [scope] (the application's); a second call does nothing while the first still runs. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        // Subscribed before the launch returns: a session that ends right after is not missed.
        val started = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            sessions.ended.collect { event ->
                // One job per session end: a note generation takes a while and must not hold up the next end.
                launch { handle(event) }
            }
        }
        job = started
        return started
    }

    /** The notes of one ended session; never throws (quiet background work). Returns how many notes were written. */
    suspend fun handle(event: ChatSessionEnded): Int {
        return try {
            val documentId = conversations.getConversationById(event.conversationId).getOrNull()?.documentId ?: return 0
            val turns = conversations.getMessages(event.conversationId).first()
                .filter { it.createdAt >= event.startedAt }
                .sortedBy { it.createdAt }
            if (turns.isEmpty()) 0 else writeNotes(documentId, turns)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
    }
}
