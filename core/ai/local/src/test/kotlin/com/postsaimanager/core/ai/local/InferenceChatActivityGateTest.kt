package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class InferenceChatActivityGateTest {

    private var now = 1_000_000L
    private val tracker = ChatActivityTracker(idleWindowMs = 120_000, clock = { now })
    private val gate = InferenceChatActivityGate(tracker)

    @Test
    @DisplayName("a chat session that was used recently protects the model, whatever runtime it runs on")
    fun `any recent chat is active`() {
        // Both engines (llama.cpp and LiteRT-LM) touch the same tracker; the gate no longer asks which runtime is resident.
        tracker.touch()

        assertThat(gate.isChatActive()).isTrue()
    }

    @Test
    @DisplayName("before any chat, and after the idle window, nothing is protected")
    fun `idle before and after`() {
        assertThat(gate.isChatActive()).isFalse()

        tracker.touch()
        now += 121_000

        assertThat(gate.isChatActive()).isFalse()
    }
}
