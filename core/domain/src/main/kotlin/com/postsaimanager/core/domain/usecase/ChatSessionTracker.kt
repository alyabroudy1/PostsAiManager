package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.ChatActivityGate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Why a chat session ended. */
enum class ChatSessionEnd {
    /** The session was ended by an explicit leave (tests and callers that do not park). */
    LEFT,

    /** Nothing was said or answered for [ChatSessionTracker.idleMs], whether the chat screen was open or not. */
    IDLE,

    /** The person opened a different chat (another document's, or the all-documents chat), which needs its own conversation. */
    DIFFERENT_CHAT,

    /** A reading (or other quiet model work) needed the engine while the session was parked, not in the foreground. */
    READING,

    /** Memory pressure, or the model was unloaded: the live conversation is gone. */
    MEMORY,

    /** The day changed since the last activity. */
    NEW_DAY,
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
 * Which chats have a live session, the short-term memory of plan 16. A session is one visit that outlives leaving the screen: it
 * begins with the chat opening (or the next message after the last session ended) and is kept for [idleMs] after the last activity
 * (a send, a reply, an open), whether the chat screen is open or not.
 *
 * ### Foreground and parked
 * A live session is either in the FOREGROUND (its chat screen is showing) or PARKED (the person left the screen). Parking keeps
 * the engine's conversation, so coming back to the same chat within [idleMs] reuses it: [begin] returns false, nothing is rebuilt
 * or warmed up again. A parked session ends early, and writes its notes (via [ended]), only when:
 *  - a DIFFERENT chat is opened ([ChatSessionEnd.DIFFERENT_CHAT]): it needs a new conversation;
 *  - quiet model work asks for the engine ([isChatActive]) while the chat is not in the foreground ([ChatSessionEnd.READING]);
 *  - [endAllParked] reports memory pressure or an unloaded model ([ChatSessionEnd.MEMORY]);
 *  - the day changed ([ChatSessionEnd.NEW_DAY]), or [idleMs] passed ([ChatSessionEnd.IDLE], noticed by [endExpiredParked] or the
 *    next [begin] / [enter]).
 * The notes are written once, when the session really ends, never on park.
 *
 * The first send or warm-up after a session ended finds [begin] returning true: the conversation is built again from the stored
 * transcript by [BuildModelContextUseCase] (card plus the last exchange), not by replaying the chat.
 *
 * [ended] is the hook for what happens at the end of a visit: `SessionNotesCollector` writes the notes of the document memory
 * there. Pure state and a clock, so the rules are unit-tested with a fake clock.
 *
 * It is also the one notion of "a chat is active" ([ChatActivityGate]): a chat is active while its session is in the FOREGROUND
 * (from the moment [begin] runs, before the model is even loaded) and not idle. A parked session does not block anything: whoever
 * asks takes the engine and the parked session ends. There is no second, shorter idle clock.
 */
@Singleton
class ChatSessionTracker internal constructor(
    private val clock: () -> Long,
    val idleMs: Long,
    private val dayOf: (Long) -> Long,
) : ChatActivityGate {

    @Inject
    constructor() : this(System::currentTimeMillis, IDLE_MS, ::localDay)

    /** For tests and other clocks: the same rules over [clock]. */
    constructor(clock: () -> Long) : this(clock, IDLE_MS, ::localDay)

    /** The time of the last activity of each live session, by conversation id. */
    private val live = mutableMapOf<String, Long>()

    /** When each live session began, by conversation id. */
    private val started = mutableMapOf<String, Long>()

    /** The chats whose screen is showing. A live session of a chat not in here is parked. */
    private val foreground = mutableSetOf<String>()

    private val _ended = MutableSharedFlow<ChatSessionEnded>(extraBufferCapacity = EVENT_BUFFER)

    /** Every session end, hot with no replay: the first collector of a later phase sees ends from then on. */
    val ended: SharedFlow<ChatSessionEnded> = _ended.asSharedFlow()

    /**
     * The chat of [conversationId] is used now (it opens, a message is sent) and its screen is in the foreground. Starts a session
     * when there is none, or when the last one went stale (idle too long, or from another day: that ends it first, with the event).
     * A parked session of a DIFFERENT chat ends here.
     *
     * @return true when this call started a new session: the engine's conversation must be built from the stored transcript.
     */
    @Synchronized
    fun begin(conversationId: String): Boolean {
        val now = clock()
        endStale(conversationId, now)
        endOtherParked(conversationId)
        val isNew = conversationId !in live
        if (isNew) started[conversationId] = now
        live[conversationId] = now
        foreground += conversationId
        return isNew
    }

    /**
     * The chat screen of [conversationId] shows (again). A parked session of this chat comes to the foreground and counts as
     * activity; a stale one ends. A parked session of a different chat ends. Without a session nothing is started: [begin] does that
     * once the first send or warm-up needs the model.
     */
    @Synchronized
    fun enter(conversationId: String) {
        val now = clock()
        endStale(conversationId, now)
        endOtherParked(conversationId)
        foreground += conversationId
        if (conversationId in live) live[conversationId] = now
    }

    /**
     * The chat screen of [conversationId] is gone from the foreground (left, or the app went to the background). The session is
     * kept, parked, until [idleMs] after its last activity or a trigger of the class doc; nothing is written now.
     */
    @Synchronized
    fun park(conversationId: String) {
        foreground -= conversationId
    }

    /** Something happened in the live session (a reply finished): the idle time starts again. A no-op without a live session. */
    @Synchronized
    fun touch(conversationId: String) {
        if (conversationId in live) live[conversationId] = clock()
    }

    /** Ends the session of [conversationId] now, whether parked or not. */
    @Synchronized
    fun leave(conversationId: String) {
        foreground -= conversationId
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

    /** Ends every parked session that has been idle for [idleMs] or has outlived its day; a periodic sweep calls it. */
    @Synchronized
    fun endExpiredParked() {
        val now = clock()
        for (id in live.keys.filter { it !in foreground }) endStale(id, now)
    }

    /** Memory pressure or an unloaded model: every parked session ends (their engine conversation is gone or about to be). */
    @Synchronized
    fun endAllParked(reason: ChatSessionEnd = ChatSessionEnd.MEMORY) {
        for (id in live.keys.filter { it !in foreground }) end(id, reason)
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

    /** Whether [conversationId] has a live session (parked or not; not counting idleness that was not noticed yet). */
    @Synchronized
    fun isLive(conversationId: String): Boolean = conversationId in live

    /** When [conversationId]'s live session began (the tracker's clock), or null without one: where a re-entered chat's divider sits. */
    @Synchronized
    fun startedAt(conversationId: String): Long? = if (conversationId in live) started[conversationId] else null

    /**
     * Whether any chat has a live session, foreground or parked, after ending the ones that expired. A READ that never ends a parked
     * session (unlike [isChatActive]): the notes job of an earlier session asks it, so that it waits instead of taking the engine
     * from a parked chat that the person may come back to.
     */
    @Synchronized
    fun hasLiveSession(): Boolean {
        val now = clock()
        for (id in live.keys.toList()) endStale(id, now)
        return live.isNotEmpty()
    }

    /** Whether [conversationId] has a live session that is parked (its screen is not showing). */
    @Synchronized
    fun isParked(conversationId: String): Boolean = conversationId in live && conversationId !in foreground

    /**
     * True while a chat is in the foreground with a live session used within [idleMs] (an idle one that nobody ended yet does not
     * count). A parked session is not active: asking is wanting the engine, so every parked session ends here
     * ([ChatSessionEnd.READING]) and its notes are queued, and the caller may go on when no foreground chat is left.
     */
    @Synchronized
    override fun isChatActive(): Boolean {
        val now = clock()
        for (id in live.keys.filter { it !in foreground }) end(id, ChatSessionEnd.READING)
        return live.any { (id, last) -> id in foreground && now - last < idleMs }
    }

    /** Ends [id]'s session when it is idle or from another day. */
    private fun endStale(id: String, now: Long) {
        val last = live[id] ?: return
        when {
            now - last >= idleMs -> end(id, ChatSessionEnd.IDLE)
            dayOf(now) != dayOf(last) -> end(id, ChatSessionEnd.NEW_DAY)
        }
    }

    /** A different chat is opened: the parked sessions of the other chats end (a chat in the foreground is not touched here). */
    private fun endOtherParked(conversationId: String) {
        for (id in live.keys.filter { it != conversationId && it !in foreground }) end(id, ChatSessionEnd.DIFFERENT_CHAT)
    }

    private fun end(conversationId: String, reason: ChatSessionEnd) {
        live.remove(conversationId)
        val startedAt = started.remove(conversationId) ?: 0L
        _ended.tryEmit(ChatSessionEnded(conversationId, reason, startedAt))
    }

    companion object {
        /** Ten minutes: how long a session is kept after the last activity, parked or not. */
        const val IDLE_MS = 10 * 60_000L
        private const val EVENT_BUFFER = 16
    }
}

private fun localDay(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
