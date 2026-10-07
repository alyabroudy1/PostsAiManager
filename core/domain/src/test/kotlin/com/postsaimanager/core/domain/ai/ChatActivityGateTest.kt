package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChatActivityGateTest {

    /** Active until [idleAtMs] of virtual time has passed (the clock is the test's own). */
    private class TimedGate(private val now: () -> Long, private val idleAtMs: Long) : ChatActivityGate {
        override fun isChatActive() = now() < idleAtMs
    }

    @Test
    @DisplayName("quiet work waits until the chat is idle, then may use the model")
    fun `waits for idle`() = runTest {
        val gate = TimedGate({ currentTime }, idleAtMs = 20_000)

        val idle = gate.awaitIdle(maxWaitMs = 60_000, pollMs = 5_000)

        assertThat(idle).isTrue()
        assertThat(currentTime).isAtLeast(20_000)
    }

    @Test
    @DisplayName("a chat that stays active is not waited for forever: the work asks to be run again later")
    fun `gives up after the maximum wait`() = runTest {
        val gate = TimedGate({ currentTime }, idleAtMs = Long.MAX_VALUE)

        val idle = gate.awaitIdle(maxWaitMs = 30_000, pollMs = 5_000)

        assertThat(idle).isFalse()
        assertThat(currentTime).isEqualTo(30_000)
    }

    @Test
    @DisplayName("an idle chat is no wait at all")
    fun `no wait when idle`() = runTest {
        assertThat(ChatActivityGate.Idle.awaitIdle()).isTrue()
        assertThat(currentTime).isEqualTo(0)
    }
}
