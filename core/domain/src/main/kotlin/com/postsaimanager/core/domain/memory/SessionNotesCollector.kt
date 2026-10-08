package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.domain.ai.ChatActivityGate
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
 * Writes the notes of a chat session when it ends (plan 16, A2 wired to A1): listens to [ChatSessionTracker.ended], queues the
 * session ([SessionNotesQueue]) and, when the queued work runs ([write]), hands the session's messages to [WriteSessionNotesUseCase].
 *
 * It runs in an application-scoped coroutine ([start]), never in a ViewModel's: leaving the chat clears the ViewModel, and that is
 * exactly when a session ends. A chat of a document writes that document's notes ([WriteSessionNotesUseCase]); the all-documents
 * chat writes the household's, each about a person or the whole household ([WriteHouseholdNotesUseCase]). A chat that was deleted
 * meanwhile has nothing to read. When no chat model is loaded, the model is busy or a chat is active, the notes are not dropped:
 * the queued work is run again later.
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
    private val writeHouseholdNotes: WriteHouseholdNotesUseCase,
    private val queue: SessionNotesQueue,
    private val chatActivity: ChatActivityGate,
) {

    private var job: Job? = null

    /** Starts listening in [scope] (the application's); a second call does nothing while the first still runs. */
    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        // Subscribed before the launch returns: a session that ends right after is not missed.
        val started = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            // Queued, never written here: the model may be busy at this moment (a reading, the last reply) and a note that finds it
            // busy must wait, not be dropped. The queued work calls [write] when the chat model is idle.
            sessions.ended.collect { event -> queue.enqueue(event) }
        }
        job = started
        return started
    }

    /**
     * The notes of one ended session, as the queued work runs it; never throws (quiet background work).
     *
     * It does not write while a chat is active (a note generation would close the live conversation of that visit) and it never
     * writes without a chat model that is idle: both answer [SessionNotesRun.Later], and the work is run again, not dropped.
     */
    suspend fun write(event: ChatSessionEnded): SessionNotesRun {
        return try {
            val conversation = conversations.getConversationById(event.conversationId).getOrNull() ?: return SessionNotesRun.Done(0)
            val turns = conversations.getMessages(event.conversationId).first()
                .filter { it.createdAt >= event.startedAt }
                .sortedBy { it.createdAt }
            if (turns.isEmpty()) return SessionNotesRun.Done(0)
            if (chatActivity.isChatActive()) return SessionNotesRun.Later
            val documentId = conversation.documentId
            SessionNotesRun.Done(
                when {
                    // The chat of a document writes the document's notes; the all-documents chat writes the household's (per person).
                    documentId != null -> writeNotes(documentId, turns, deferIfUnavailable = true)
                    else -> writeHouseholdNotes(turns, deferIfUnavailable = true)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionNotesDeferred) {
            SessionNotesRun.Later
        } catch (e: Exception) {
            SessionNotesRun.Done(0)
        }
    }
}

/** What one run of a session's queued notes came to. */
sealed interface SessionNotesRun {

    /** Finished: [written] notes were stored (0 when there was nothing worth keeping, or the chat is gone). */
    data class Done(val written: Int) : SessionNotesRun

    /** The model is not free for it yet (a chat is active, none is resident, or it is busy): run again later. */
    data object Later : SessionNotesRun
}

/**
 * Where a session's notes wait until the chat model is idle: one entry per ended session, so that the same session queued twice is
 * written once. Implemented over WorkManager in `core/data` (it survives the app being closed); [None] where nothing is queued.
 */
interface SessionNotesQueue {

    /** Queues the notes of [event]; idempotent per session (the conversation and the time the session began). */
    fun enqueue(event: ChatSessionEnded)

    object None : SessionNotesQueue {
        override fun enqueue(event: ChatSessionEnded) = Unit
    }
}
