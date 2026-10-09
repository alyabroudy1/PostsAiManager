package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.samplingFor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** When the "Questions" reader shows the page picture, and how it samples per backend. */
class QuestionReaderPolicyTest {

    private fun letter(lines: Int, chars: Int = 40, unreadable: Int = 0) =
        GemmaLetter((1..lines).map { GemmaLine("L$it", 1, "body", 0f, 0f, "x".repeat(chars)) }, emptyList(), unreadable)

    @Test
    @DisplayName("a letter the OCR read well goes without the picture")
    fun `strong text`() {
        val d = QaImageDecision.decide(letter(lines = 40), alwaysSend = false)

        assertThat(d.send).isFalse()
        assertThat(d.reason).contains("strong")
    }

    @Test
    @DisplayName("few lines, few characters or many unreadable lines send the picture, each with its reason")
    fun `weak text`() {
        assertThat(QaImageDecision.decide(letter(lines = QaImageDecision.MIN_LINES - 1, chars = 100), false).send).isTrue()
        assertThat(QaImageDecision.decide(letter(lines = 20, chars = 5), false).reason).contains("chars")
        val unreadable = QaImageDecision.decide(letter(lines = 40, unreadable = 20), false)
        assertThat(unreadable.send).isTrue()
        assertThat(unreadable.reason).contains("unreadable")
    }

    @Test
    @DisplayName("the thresholds are inclusive: exactly the minimum lines and characters is strong; a few unreadable lines are tolerated")
    fun `boundaries`() {
        assertThat(QaImageDecision.decide(letter(lines = QaImageDecision.MIN_LINES, chars = 30), false).send).isFalse()
        assertThat(QaImageDecision.decide(letter(lines = 40, unreadable = 5), false).send).isFalse()
        // Exactly a quarter unreadable is still tolerated; just over is not.
        assertThat(QaImageDecision.decide(letter(lines = 30, unreadable = 10), false).send).isFalse()
        assertThat(QaImageDecision.decide(letter(lines = 30, unreadable = 11), false).send).isTrue()
    }

    @Test
    @DisplayName("the debug override always sends it")
    fun `override`() {
        val d = QaImageDecision.decide(letter(lines = 100), alwaysSend = true)

        assertThat(d.send).isTrue()
        assertThat(d.reason).contains("override")
    }

    @Test
    @DisplayName("sampling: greedy (top-k 1) on every backend")
    fun `sampling`() {
        assertThat(QaSampling.PURPOSE).isEqualTo(SamplingPurpose.STRUCTURED)
        assertThat(samplingFor(QaSampling.PURPOSE).topK).isEqualTo(1)
    }
}
