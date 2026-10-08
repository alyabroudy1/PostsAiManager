package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.SamplingConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SamplingForTest {

    @Test
    @DisplayName("a structured call is greedy: top-k 1, nothing left to the random draw")
    fun `structured is greedy`() {
        val sampling = samplingFor(SamplingPurpose.STRUCTURED)

        assertThat(sampling.topK).isEqualTo(1)
        assertThat(sampling.temperature).isGreaterThan(0f)
        assertThat(sampling.topP).isEqualTo(1f)
    }

    @Test
    @DisplayName("free text keeps the app's usual sampling")
    fun `free text is the usual sampling`() {
        assertThat(samplingFor(SamplingPurpose.FREE_TEXT)).isEqualTo(SamplingConfig())
    }

    @Test
    @DisplayName("a structured request (the reader's JSON turn, the text step, the follow-ups) is greedy unless it says otherwise")
    fun `structured requests default to greedy`() {
        val request = StructuredRequest(system = "s", prompt = "p", schema = "{}")
        val greedy = samplingFor(SamplingPurpose.STRUCTURED)

        assertThat(request.topK).isEqualTo(greedy.topK)
        assertThat(request.temperature).isEqualTo(greedy.temperature)
        assertThat(request.topP).isEqualTo(greedy.topP)
    }

    @Test
    @DisplayName("a free-text request keeps the default sampling: only constrained calls are greedy")
    fun `free text requests are not greedy`() {
        val request = AiRequest(prompt = "hello")

        assertThat(request.topK).isGreaterThan(1)
    }
}
