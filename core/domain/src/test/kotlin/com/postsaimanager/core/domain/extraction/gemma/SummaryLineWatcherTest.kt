package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The summary is found in the answer while it streams, once, as soon as its line ends. */
class SummaryLineWatcherTest {

    @Test
    @DisplayName("nothing until the SUMMARY line has ended; then the summary, once")
    fun `streamed`() {
        val w = SummaryLineWatcher()

        assertThat(w.feed("SUMM")).isNull()
        assertThat(w.feed("SUMMARY: A reminder to pay")).isNull()
        assertThat(w.feed("SUMMARY: A reminder to pay a bill.\n")).isEqualTo("A reminder to pay a bill.")
        assertThat(w.hasDelivered).isTrue()
        assertThat(w.feed("SUMMARY: A reminder to pay a bill.\nSENDER: Stadtwerke\n")).isNull()
        assertThat(w.finish("SUMMARY: A reminder to pay a bill.\nSENDER: Stadtwerke")).isNull()
    }

    @Test
    @DisplayName("markdown around the label is understood; a summary that is none is no summary")
    fun `tolerant`() {
        assertThat(SummaryLineWatcher().feed("**SUMMARY:** Brief.\nSENDER: X\n")).isEqualTo("Brief.")
        assertThat(SummaryLineWatcher().feed("summary: none\nSENDER: X\n")).isNull()
        assertThat(SummaryLineWatcher().feed("SENDER: X\nTYPE: bill\n")).isNull()
    }

    @Test
    @DisplayName("a summary line that is the last text (no line break yet) is only taken from the final answer")
    fun `final answer`() {
        val w = SummaryLineWatcher()

        assertThat(w.feed("SUMMARY: Last line")).isNull()
        assertThat(w.finish("SUMMARY: Last line")).isEqualTo("Last line")
        assertThat(w.finish("SUMMARY: Last line")).isNull()
    }

    @Test
    @DisplayName("the answer parser reads the summary as a label of its own")
    fun `label`() {
        val answers = QuestionAnswerParser.parse("SUMMARY: A bill.\nSENDER: Stadtwerke | company")

        assertThat(answers[QaLabel.SUMMARY]).isEqualTo("A bill.")
        assertThat(answers[QaLabel.SENDER]).isEqualTo("Stadtwerke | company")
        assertThat(QuestionAnswerParser.labelled("not a label line")).isNull()
        assertThat(QuestionAnswerParser.labelled("- **TITLE:** Strom")).isEqualTo(QaLabel.TITLE to "Strom")
    }
}
