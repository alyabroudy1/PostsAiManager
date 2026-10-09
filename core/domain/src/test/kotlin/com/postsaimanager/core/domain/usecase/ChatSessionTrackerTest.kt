package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The session boundary of plan 16: a visit ends when the chat is left or after 10 idle minutes, and says so. */
class ChatSessionTrackerTest {

    private var now = 1_000_000L
    private val tracker = ChatSessionTracker { now }

    private val minute = 60_000L

    @Test
    @DisplayName("the first use of a chat begins a session; later uses continue it")
    fun `first begin is new`() {
        assertThat(tracker.begin("c")).isTrue()
        now += 5 * minute
        assertThat(tracker.begin("c")).isFalse()
        assertThat(tracker.isLive("c")).isTrue()
    }

    @Test
    @DisplayName("leaving the chat ends the session, fires the event once, and the next begin is a new session")
    fun `leave ends the session`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(events) }
        tracker.begin("c")

        tracker.leave("c")
        tracker.leave("c")

        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.LEFT, startedAt = 1_000_000L))
        assertThat(tracker.isLive("c")).isFalse()
        assertThat(tracker.begin("c")).isTrue()
        job.cancel()
    }

    @Test
    @DisplayName("ten idle minutes end the session (fake clock) and fire the event; nine do not")
    fun `idle ends the session`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(events) }
        tracker.begin("c")

        now += 9 * minute
        assertThat(tracker.endIfIdle("c")).isFalse()
        assertThat(events).isEmpty()

        now += minute
        assertThat(tracker.endIfIdle("c")).isTrue()
        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.IDLE, startedAt = 1_000_000L))
        assertThat(tracker.isLive("c")).isFalse()
        job.cancel()
    }

    @Test
    @DisplayName("activity (a finished reply) restarts the idle time")
    fun `touch restarts idle`() {
        tracker.begin("c")
        now += 8 * minute
        tracker.touch("c")
        now += 8 * minute
        assertThat(tracker.endIfIdle("c")).isFalse()
        assertThat(tracker.idleInMs("c")).isEqualTo(2 * minute)
    }

    @Test
    @DisplayName("a send after the idle time starts a new session, and the old one ended with its event")
    fun `begin after idle is new`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(events) }
        tracker.begin("c")

        now += 11 * minute

        assertThat(tracker.begin("c")).isTrue()
        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.IDLE, startedAt = 1_000_000L))
        job.cancel()
    }

    @Test
    @DisplayName("the end event says when the session began, not when it was last used")
    fun `event carries the session start`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(events) }
        tracker.begin("c")
        now += 3 * minute
        tracker.begin("c")
        tracker.touch("c")

        tracker.leave("c")
        // The next session of the same chat starts later and says so.
        now += minute
        tracker.begin("c")
        now += minute
        tracker.leave("c")

        assertThat(events.map { it.startedAt }).containsExactly(1_000_000L, 1_000_000L + 4 * minute).inOrder()
        job.cancel()
    }

    @Test
    @DisplayName("discarding a session (a new chat) ends it without an event")
    fun `discard is silent`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(events) }
        tracker.begin("c")

        tracker.discard("c")

        assertThat(events).isEmpty()
        assertThat(tracker.isLive("c")).isFalse()
        job.cancel()
    }

    @Test
    @DisplayName("one clock: a chat is active for the whole session (ten idle minutes), not for a shorter window that frees the model between two messages")
    fun `one clock for chat active`() {
        assertThat(tracker.isChatActive()).isFalse()
        tracker.begin("c")
        assertThat(tracker.isChatActive()).isTrue()

        now += 9 * minute
        assertThat(tracker.isChatActive()).isTrue()

        // A finished reply restarts the same clock.
        tracker.touch("c")
        now += 9 * minute
        assertThat(tracker.isChatActive()).isTrue()

        // The idle time passed: the chat is not active any more, whether or not the session end was noticed yet.
        now += minute
        assertThat(tracker.isChatActive()).isFalse()
    }

    @Test
    @DisplayName("leaving the chat ends the activity at once; another chat keeps it")
    fun `leaving ends activity`() {
        tracker.begin("a")
        tracker.begin("b")

        tracker.leave("a")
        assertThat(tracker.isChatActive()).isTrue()

        tracker.leave("b")
        assertThat(tracker.isChatActive()).isFalse()
    }

    @Test
    @DisplayName("sessions of different chats are independent")
    fun `sessions are per chat`() {
        tracker.begin("a")
        now += 6 * minute
        tracker.begin("b")
        now += 5 * minute

        assertThat(tracker.endIfIdle("a")).isTrue()
        assertThat(tracker.endIfIdle("b")).isFalse()
    }

    private fun kotlinx.coroutines.test.TestScope.collectEnds(into: MutableList<ChatSessionEnded>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ended.toList(into) }

    @Test
    @DisplayName("leaving parks the session: no event, and re-entering within 10 minutes reuses it (no new session)")
    fun `park then re-enter reuses`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        assertThat(tracker.begin("c")).isTrue()

        tracker.park("c")
        now += 9 * minute
        tracker.enter("c")

        assertThat(events).isEmpty()
        assertThat(tracker.begin("c")).isFalse()
        assertThat(tracker.isParked("c")).isFalse()
    }

    @Test
    @DisplayName("parked past 10 minutes: re-entering ends it once with the notes event, and the next begin is a new session")
    fun `park then re-enter after ten minutes rebuilds`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("c")
        tracker.park("c")

        now += 10 * minute
        tracker.enter("c")
        assertThat(tracker.begin("c")).isTrue()

        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.IDLE, startedAt = 1_000_000L))
    }

    @Test
    @DisplayName("the sweep ends a parked session after 10 idle minutes, once, and leaves a foreground one to its screen")
    fun `sweep ends parked sessions`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("parked")
        tracker.begin("open")
        tracker.park("parked")

        now += 9 * minute
        tracker.endExpiredParked()
        assertThat(events).isEmpty()

        now += minute
        tracker.endExpiredParked()
        tracker.endExpiredParked()

        assertThat(events.map { it.conversationId to it.reason }).containsExactly("parked" to ChatSessionEnd.IDLE)
        assertThat(tracker.isLive("open")).isTrue()
    }

    @Test
    @DisplayName("opening a different chat ends the parked session of the other one")
    fun `different chat ends parked`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("a")
        tracker.park("a")

        tracker.begin("b")

        assertThat(events.map { it.conversationId to it.reason }).containsExactly("a" to ChatSessionEnd.DIFFERENT_CHAT)
        assertThat(tracker.isLive("a")).isFalse()
        assertThat(tracker.isLive("b")).isTrue()
    }

    @Test
    @DisplayName("a reading that asks while the chat is parked ends the session, and may run")
    fun `reading ends parked`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("c")
        tracker.park("c")

        assertThat(tracker.isChatActive()).isFalse()

        assertThat(events.map { it.reason }).containsExactly(ChatSessionEnd.READING)
        assertThat(tracker.isLive("c")).isFalse()
    }

    @Test
    @DisplayName("a reading that asks while the chat is in the foreground waits, and the session stays")
    fun `reading waits for the foreground`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("c")

        assertThat(tracker.isChatActive()).isTrue()
        now += 5 * minute
        assertThat(tracker.isChatActive()).isTrue()

        assertThat(events).isEmpty()
        assertThat(tracker.isLive("c")).isTrue()
    }

    @Test
    @DisplayName("memory pressure ends the parked sessions, not the one in the foreground")
    fun `memory pressure ends parked`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("parked")
        tracker.begin("open")
        tracker.park("parked")

        tracker.endAllParked()

        assertThat(events.map { it.conversationId to it.reason }).containsExactly("parked" to ChatSessionEnd.MEMORY)
        assertThat(tracker.isLive("open")).isTrue()
    }

    @Test
    @DisplayName("a session from yesterday ends on the new day even within 10 minutes")
    fun `new day ends the session`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        // Midnight falls one minute after the session began.
        val midnight = 1_000_000L + minute
        val dayTracker = ChatSessionTracker({ now }, ChatSessionTracker.IDLE_MS) { if (it >= midnight) 2L else 1L }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { dayTracker.ended.toList(events) }
        dayTracker.begin("c")
        dayTracker.park("c")

        now += minute

        assertThat(dayTracker.begin("c")).isTrue()
        assertThat(events.map { it.reason }).containsExactly(ChatSessionEnd.NEW_DAY)
    }

    @Test
    @DisplayName("the notes event fires only when the session really ends: not on park, not on re-enter, once on the end")
    fun `event only on the real end`() = runTest {
        val events = mutableListOf<ChatSessionEnded>()
        collectEnds(events)
        tracker.begin("c")
        tracker.park("c")
        tracker.enter("c")
        tracker.park("c")
        assertThat(events).isEmpty()

        tracker.isChatActive()
        tracker.isChatActive()
        tracker.endAllParked()
        tracker.endExpiredParked()

        assertThat(events).hasSize(1)
    }
}
