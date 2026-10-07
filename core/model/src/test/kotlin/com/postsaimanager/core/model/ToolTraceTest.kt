package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ToolTraceTest {

    private val exchanges = listOf(
        ToolExchange("load_skill", """{"skill_name":"schedule-reminder"}""", """{"skill_instructions":"Zeile 1\n\"zwei\""}"""),
        ToolExchange("run_intent", """{"intent":"schedule_notification","parameters":"{\"day\":7}"}""", """{"status":"proposed"}"""),
    )

    @Test
    fun `a trace survives the round trip, quotes and newlines included`() {
        assertThat(ToolTrace.decode(ToolTrace.encode(exchanges))).isEqualTo(exchanges)
    }

    @Test
    @DisplayName("a reply without tool calls is stored as nothing, not as an empty array")
    fun `an empty trace encodes to an empty string`() {
        assertThat(ToolTrace.encode(emptyList())).isEmpty()
    }

    @Test
    @DisplayName("anything that is not a trace reads as no trace: the column may hold another feature's JSON, or nothing")
    fun `junk decodes to nothing`() {
        assertThat(ToolTrace.decode(null)).isEmpty()
        assertThat(ToolTrace.decode("")).isEmpty()
        assertThat(ToolTrace.decode("   ")).isEmpty()
        assertThat(ToolTrace.decode("{not json")).isEmpty()
        assertThat(ToolTrace.decode("""{"kind":"form"}""")).isEmpty()
    }
}
