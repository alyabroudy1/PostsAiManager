package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SessionNotesFormatTest {

    @Test
    fun `NONE in any form is no notes`() {
        listOf("NONE", " none ", "None.", "").forEach { assertThat(SessionNotesFormat.parse(it)).isEmpty() }
    }

    @Test
    fun `one note a line, list markers and quotes dropped`() {
        val notes = SessionNotesFormat.parse("- The user paid on 5 Oct.\n2. \"The user will call Monday.\"\n• Third one\n\n")

        assertThat(notes).containsExactly("The user paid on 5 Oct.", "The user will call Monday.", "Third one").inOrder()
    }

    @Test
    fun `a NONE line among notes is not a note`() {
        assertThat(SessionNotesFormat.parse("A fact\nNONE")).containsExactly("A fact")
    }

    @Test
    fun `no more than twice the cap is read, so the verifier has a choice`() {
        val answer = (1..20).joinToString("\n") { "fact $it" }

        assertThat(SessionNotesFormat.parse(answer)).hasSize(SessionNotesFormat.MAX_NOTES * 2)
    }

    @Test
    fun `the question carries the conversation, the notes already kept and the instruction`() {
        val prompt = SessionNotesFormat.prompt(
            listOf(SessionNotesFormat.Turn(true, "I paid it"), SessionNotesFormat.Turn(false, "Great")),
            listOf("Reminder set"),
        )

        assertThat(prompt).contains("User: I paid it")
        assertThat(prompt).contains("Assistant: Great")
        assertThat(prompt).contains("- Reminder set")
        assertThat(prompt).contains("durable facts or decisions")
        assertThat(prompt).contains("NONE")
    }

    @Test
    fun `a long conversation shows its newest turns within the budget`() {
        val turns = (1..200).map { SessionNotesFormat.Turn(true, "message number $it " + "x".repeat(50)) }

        val prompt = SessionNotesFormat.prompt(turns, emptyList())

        assertThat(prompt).contains("message number 200")
        assertThat(prompt).doesNotContain("message number 1 ")
        assertThat(prompt.length).isLessThan(SessionNotesFormat.MAX_TRANSCRIPT_CHARS + 1_000)
    }
}
