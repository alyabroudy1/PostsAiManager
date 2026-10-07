package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChatActivityTrackerTest {

    private var now = 1_000_000L
    private val tracker = ChatActivityTracker(idleWindowMs = 120_000, clock = { now })

    @Test
    @DisplayName("before the chat ever used the model there is nothing to protect")
    fun `never used is idle`() {
        assertThat(tracker.isRecent()).isFalse()
    }

    @Test
    @DisplayName("the chat counts as active for the idle window after its last use, and is idle after it")
    fun `active within the window`() {
        tracker.touch()

        now += 119_000
        assertThat(tracker.isRecent()).isTrue()

        now += 2_000
        assertThat(tracker.isRecent()).isFalse()
    }

    @Test
    @DisplayName("every use starts the window again, so a conversation in progress stays protected")
    fun `use extends the window`() {
        tracker.touch()
        now += 100_000
        tracker.touch()
        now += 100_000

        assertThat(tracker.isRecent()).isTrue()
    }
}
