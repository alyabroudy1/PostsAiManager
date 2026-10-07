package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import org.junit.jupiter.api.Test

class DocumentMemoryFormatTest {

    private fun note(text: String, updatedAt: Long, pinned: Boolean = false) =
        DocumentNote("n$updatedAt", "d", text, NoteSource.AI, createdAt = updatedAt, updatedAt = updatedAt, pinned = pinned)

    @Test
    fun `no notes give an empty slot`() {
        assertThat(DocumentMemoryFormat.format(emptyList())).isEmpty()
    }

    @Test
    fun `notes are bullet lines, pinned first, then the newest`() {
        val text = DocumentMemoryFormat.format(
            listOf(note("old", 1), note("newest", 3), note("pinned but old", 0, pinned = true), note("middle", 2)),
        )

        assertThat(text.lines()).containsExactly("- pinned but old", "- newest", "- middle", "- old").inOrder()
    }

    @Test
    fun `a note is one line of single spaces`() {
        assertThat(DocumentMemoryFormat.format(listOf(note("Paid on\n5 Oct,  says the user", 1)))).isEqualTo("- Paid on 5 Oct, says the user")
    }

    @Test
    fun `at most ten notes are read`() {
        val notes = (1..15L).map { note("note $it", it) }

        val lines = DocumentMemoryFormat.format(notes).lines()

        assertThat(lines).hasSize(DocumentMemoryFormat.MAX_NOTES)
        assertThat(lines.first()).isEqualTo("- note 15")
        assertThat(lines.last()).isEqualTo("- note 6")
    }

    @Test
    fun `the character cap holds and a note that does not fit ends the list`() {
        val long = "x".repeat(300)
        val notes = listOf(note(long, 4), note(long, 3), note(long, 2), note("short", 1))

        val text = DocumentMemoryFormat.format(notes)

        assertThat(text.length).isAtMost(DocumentMemoryFormat.MAX_CHARS)
        // 302 + 1 + 302 = 605 fits, the third (908) does not; the short older note must not jump the queue.
        assertThat(text.lines()).hasSize(2)
        assertThat(text).doesNotContain("short")
    }

    @Test
    fun `a single note longer than the cap is cut`() {
        val text = DocumentMemoryFormat.format(listOf(note("y".repeat(2000), 1)))

        assertThat(text.length).isEqualTo(DocumentMemoryFormat.MAX_CHARS)
        assertThat(text).endsWith("…")
    }

    @Test
    fun `blank notes are skipped`() {
        assertThat(DocumentMemoryFormat.format(listOf(note("   ", 2), note("kept", 1)))).isEqualTo("- kept")
    }
}
