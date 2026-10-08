package com.postsaimanager.core.domain.reading

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Letters finishing close together share one notification; a notification that is gone starts a new one. */
class ReadingFinishedBatchTest {

    private val window = 10 * 60 * 1000L
    private val batch = ReadingFinishedBatch(window)

    private fun letter(id: String) = UnderstoodLetter(id, title = "Letter $id", highlight = null, private = false)

    @Test
    fun `letters finishing within the window while the notification still shows are grouped`() {
        assertThat(batch.add(letter("a"), now = 1_000, stillShown = false).map { it.documentId }).containsExactly("a")
        assertThat(batch.add(letter("b"), now = 4 * 60_000, stillShown = true).map { it.documentId }).containsExactly("a", "b").inOrder()
        assertThat(batch.add(letter("c"), now = 8 * 60_000, stillShown = true).map { it.documentId }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `a notification that was dismissed or opened starts a new batch`() {
        batch.add(letter("a"), now = 1_000, stillShown = false)
        assertThat(batch.add(letter("b"), now = 2_000, stillShown = false).map { it.documentId }).containsExactly("b")
    }

    @Test
    fun `a letter after the window starts a new batch even if the old notification is still there`() {
        batch.add(letter("a"), now = 0, stillShown = false)
        assertThat(batch.add(letter("b"), now = window + 1, stillShown = true).map { it.documentId }).containsExactly("b")
    }

    @Test
    fun `the window runs from the last letter, so a steady stream stays one batch`() {
        batch.add(letter("a"), now = 0, stillShown = false)
        batch.add(letter("b"), now = window - 1, stillShown = true)
        assertThat(batch.add(letter("c"), now = 2 * window - 2, stillShown = true)).hasSize(3)
    }

    @Test
    fun `the same letter read again replaces its entry instead of counting twice`() {
        batch.add(letter("a"), now = 0, stillShown = false)
        val again = batch.add(letter("a").copy(title = "New"), now = 1, stillShown = true)
        assertThat(again).hasSize(1)
        assertThat(again.single().title).isEqualTo("New")
    }
}
