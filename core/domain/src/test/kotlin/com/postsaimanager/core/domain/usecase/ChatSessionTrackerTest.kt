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

        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.LEFT))
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
        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.IDLE))
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
        assertThat(events).containsExactly(ChatSessionEnded("c", ChatSessionEnd.IDLE))
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
    @DisplayName("sessions of different chats are independent")
    fun `sessions are per chat`() {
        tracker.begin("a")
        now += 6 * minute
        tracker.begin("b")
        now += 5 * minute

        assertThat(tracker.endIfIdle("a")).isTrue()
        assertThat(tracker.endIfIdle("b")).isFalse()
    }
}
