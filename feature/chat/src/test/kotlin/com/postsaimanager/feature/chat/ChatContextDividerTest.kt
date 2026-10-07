package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The quiet line above the messages the assistant still reads (plan 16, A1): only when there are older messages. */
class ChatContextDividerTest {

    private fun messages(count: Int) = List(count) { ChatMessage(id = "m$it", text = "message $it", isUser = it % 2 == 0) }

    private fun dividers(entries: List<TimelineEntry>) = entries.filterIsInstance<TimelineEntry.ContextDivider>()

    private fun build(messages: List<ChatMessage>, contextStartId: String?) =
        ChatTimeline.build(messages, cards = emptyList(), error = false, live = false, thinking = false, contextStartId = contextStartId)

    @Test
    @DisplayName("with older messages, the divider sits directly above the first message of the tail")
    fun `divider above the tail`() {
        val entries = build(messages(6), contextStartId = "m4")

        assertThat(dividers(entries)).hasSize(1)
        // Newest first: m5, m4, then the divider, then everything older.
        val keys = entries.map { it.key }
        assertThat(keys.indexOf(TimelineEntry.ContextDivider.key)).isEqualTo(keys.indexOf("m4") + 1)
        assertThat(keys.indexOf("m3")).isGreaterThan(keys.indexOf(TimelineEntry.ContextDivider.key))
    }

    @Test
    @DisplayName("no divider when nothing is older than the tail")
    fun `no divider without older messages`() {
        assertThat(dividers(build(messages(2), contextStartId = "m0"))).isEmpty()
        assertThat(dividers(build(messages(1), contextStartId = "m0"))).isEmpty()
    }

    @Test
    @DisplayName("no divider for a chat without a tail, or when the tail's first message is not in the list")
    fun `no divider without a tail`() {
        assertThat(dividers(build(messages(6), contextStartId = null))).isEmpty()
        assertThat(dividers(build(messages(6), contextStartId = "gone"))).isEmpty()
        assertThat(dividers(build(emptyList(), contextStartId = "m0"))).isEmpty()
    }

    @Test
    @DisplayName("a live session grows below the divider: new messages never move it")
    fun `divider stays when the chat grows`() {
        val before = build(messages(6), contextStartId = "m4").map { it.key }
        val after = build(messages(8), contextStartId = "m4").map { it.key }

        assertThat(after.indexOf(TimelineEntry.ContextDivider.key) - after.indexOf("m4")).isEqualTo(1)
        assertThat(before.indexOf(TimelineEntry.ContextDivider.key) - before.indexOf("m4")).isEqualTo(1)
    }
}
