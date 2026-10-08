package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.ChatActivityGate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Why a chat session ended. */
enum class ChatSessionEnd {
    /** The person left the chat screen. */
    LEFT,

    /** Nothing was said or answered for [ChatSessionTracker.idleMs]. */
    IDLE,
}

/**
 * A chat session ended: the visit of [conversationId] is over and its next send starts a fresh one.
 *
 * @param startedAt the tracker's clock when the session began. The messages of the session are the stored ones created at or after
 *   it (a message is created after the session began, never before), which is how the notes of the session are written from
 *   exactly what was said in it.
 */
data class ChatSessionEnded(val conversationId: String, val reason: ChatSessionEnd, val startedAt: Long = 0L)

/**
 * Which chats have a live session, the short-term memory of plan 16. A session is one visit: it begins with the chat opening (or the
 * next message after the last session ended) and ends when the person leaves the chat screen, or after [idleMs] with nothing said.
 *
 * While a session is live the engine keeps its conversation as it is (every turn of the visit, until the engine compacts it). The
 * first send or warm-up after a session ended finds [begin] returning true: the conversation is built again from the stored transcript
 * by [BuildModelContextUseCase] (card plus the last exchange), not by replaying the chat.
 *
 * [ended] is the hook for what happens at the end of a visit: `SessionNotesCollector` writes the notes of the document memory
 * there. Pure state and a clock, so the rules are unit-tested with a fake clock.
 *
 * It is also the one notion of "a chat is active" ([ChatActivityGate]): a chat is active from the moment a session begins (before the
 * model is even loaded for it) until the session ends, by leaving the screen or after [idleMs] with nothing said. Quiet model work
 * (a reading, a summary, the notes) waits for that; there is no second, shorter idle clock that could take the model between two
 * messages of a live visit.
 */
@Singleton
class ChatSessionTracker internal constructor(
    private val clock: () -> Long,
    val idleMs: Long,
) : ChatActivityGate {

    @Inject
    constructor() : this(System::currentTimeMillis, IDLE_MS)

    /** For tests and other clocks: the same rules over [clock]. */
    constructor(clock: () -> Long) : this(clock, IDLE_MS)

    /** The time of the last activity of each live session, by conversation id. */
    private val live = mutableMapOf<String, Long>()

    /** When each live session began, by conversation id. */
    private val started = mutableMapOf<String, Long>()

    private val _ended = MutableSharedFlow<ChatSessionEnded>(extraBufferCapacity = EVENT_BUFFER)

    /** Every session end, hot with no replay: the first collector of a later phase sees ends from then on. */
    val ended: SharedFlow<ChatSessionEnded> = _ended.asSharedFlow()

    /**
     * The chat of [conversationId] is used now (it opens, a message is sent). Starts a session when there is none, or when the last
     * one has been idle too long (which ends it first, with the event).
     *
     * @return true when this call started a new session: the engine's conversation must be built from the stored transcript.
     */
    @Synchronized
    fun begin(conversationId: String): Boolean {
        val now = clock()
        val last = live[conversationId]
        if (last != null && now - last >= idleMs) end(conversationId, ChatSessionEnd.IDLE)
        val isNew = conversationId !in live
        if (isNew) started[conversationId] = now
        live[conversationId] = now
        return isNew
    }

    /** Something happened in the live session (a reply finished): the idle time starts again. A no-op without a live session. */
    @Synchronized
    fun touch(conversationId: String) {
        if (conversationId in live) live[conversationId] = clock()
    }

    /** The person left the chat screen. */
    @Synchronized
    fun leave(conversationId: String) {
        if (conversationId in live) end(conversationId, ChatSessionEnd.LEFT)
    }

    /**
     * Ends the session when it has been idle for [idleMs].
     *
     * @return true when it ended now.
     */
    @Synchronized
    fun endIfIdle(conversationId: String): Boolean {
        val last = live[conversationId] ?: return false
        if (clock() - last < idleMs) return false
        end(conversationId, ChatSessionEnd.IDLE)
        return true
    }

    /** Milliseconds until [conversationId]'s session is idle, or null without a live session. */
    @Synchronized
    fun idleInMs(conversationId: String): Long? = live[conversationId]?.let { (idleMs - (clock() - it)).coerceAtLeast(0) }

    /** The session is gone without an event: its chat was deleted (a new chat), so there is nothing to keep notes of. */
    @Synchronized
    fun discard(conversationId: String) {
        live.remove(conversationId)
        started.remove(conversationId)
    }

    /** Whether [conversationId] has a live session (not counting idleness that was not noticed yet). */
    @Synchronized
    fun isLive(conversationId: String): Boolean = conversationId in live

    /** True while any chat has a live session that was used within [idleMs] (an idle one that nobody ended yet does not count). */
    @Synchronized
    override fun isChatActive(): Boolean {
        val now = clock()
        return live.values.any { now - it < idleMs }
    }

    private fun end(conversationId: String, reason: ChatSessionEnd) {
        live.remove(conversationId)
        val startedAt = started.remove(conversationId) ?: 0L
        _ended.tryEmit(ChatSessionEnded(conversationId, reason, startedAt))
    }

    companion object {
        /** Ten minutes: how long a person may leave the open chat alone before the visit counts as over. */
        const val IDLE_MS = 10 * 60_000L
        private const val EVENT_BUFFER = 16
    }
}
