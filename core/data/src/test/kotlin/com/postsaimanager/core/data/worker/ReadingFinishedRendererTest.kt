package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.reading.ReadingFinishedContent
import com.postsaimanager.core.domain.reading.ReadingFinishedLine
import com.postsaimanager.core.domain.reading.ReadingHighlight
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The words of the "Letter understood" notification: the private version, and the public one that never names anything. */
class ReadingFinishedRendererTest {

    private val words = object : ReadingFinishedWords {
        override fun title() = "Letter understood"
        override fun groupTitle(count: Int) = "$count letters understood"
        override fun publicText() = "A letter was understood"
        override fun due(date: LocalDate) = "Due ${date.dayOfMonth} Oct"
        override fun date(date: LocalDate) = "${date.dayOfMonth} Oct"
        override fun more(count: Int) = "+$count more"
        override fun groupHidden() = "Open the app to see them"
    }

    private val due = ReadingHighlight(LocalDate.of(2026, 10, 15), "104,20 €", isDeadline = true)

    private fun single(title: String?, highlight: ReadingHighlight?) =
        ReadingFinishedContent(count = 1, documentId = "d1", title = title, highlight = highlight, lines = emptyList(), hiddenCount = 0)

    @Test
    fun `one letter says its name and the most important line`() {
        val text = ReadingFinishedRenderer.render(single("Stadtwerke · Rechnung Juli", due), words)
        assertThat(text.title).isEqualTo("Letter understood")
        assertThat(text.text).isEqualTo("Stadtwerke · Rechnung Juli · Due 15 Oct · 104,20 €")
        assertThat(text.lines).containsExactly("Stadtwerke · Rechnung Juli", "Due 15 Oct · 104,20 €").inOrder()
    }

    @Test
    fun `the public version says only that a letter was understood, whatever the content`() {
        val text = ReadingFinishedRenderer.render(single("Stadtwerke · Rechnung Juli", due), words)
        assertThat(text.publicTitle).isEqualTo("Letter understood")
        assertThat(text.publicText).isEqualTo("A letter was understood")
        assertThat(text.publicText).doesNotContain("Stadtwerke")
        assertThat(text.publicText).doesNotContain("104")
    }

    @Test
    fun `a letter that may not be named says the generic sentence in both versions`() {
        val text = ReadingFinishedRenderer.render(single(null, null), words)
        assertThat(text.text).isEqualTo("A letter was understood")
        assertThat(text.lines).isEmpty()
        assertThat(text.publicText).isEqualTo("A letter was understood")
    }

    @Test
    fun `an appointment is a plain date, not a due date`() {
        val text = ReadingFinishedRenderer.render(single(null, ReadingHighlight(LocalDate.of(2026, 11, 5), null, isDeadline = false)), words)
        assertThat(text.text).isEqualTo("5 Oct")
    }

    @Test
    fun `a group counts the letters, lists the ones that may be named and counts the rest`() {
        val content = ReadingFinishedContent(
            count = 3, documentId = null, title = null, highlight = null,
            lines = listOf(ReadingFinishedLine("Stadtwerke", due), ReadingFinishedLine("Finanzamt", null)), hiddenCount = 1,
        )
        val text = ReadingFinishedRenderer.render(content, words)
        assertThat(text.title).isEqualTo("3 letters understood")
        assertThat(text.text).isEqualTo("Stadtwerke, Finanzamt")
        assertThat(text.lines).containsExactly("Stadtwerke · Due 15 Oct · 104,20 €", "Finanzamt", "+1 more").inOrder()
        // The lock screen sees the count only.
        assertThat(text.publicTitle).isEqualTo("3 letters understood")
        assertThat(text.publicText).isNull()
    }

    @Test
    fun `a group where nothing may be named says so and lists nothing`() {
        val content = ReadingFinishedContent(count = 2, documentId = null, title = null, highlight = null, lines = emptyList(), hiddenCount = 2)
        val text = ReadingFinishedRenderer.render(content, words)
        assertThat(text.title).isEqualTo("2 letters understood")
        assertThat(text.text).isEqualTo("Open the app to see them")
        assertThat(text.lines).isEmpty()
    }
}
