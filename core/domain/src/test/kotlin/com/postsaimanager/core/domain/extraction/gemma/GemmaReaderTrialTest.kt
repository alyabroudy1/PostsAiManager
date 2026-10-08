package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.ReadAgainWithGemmaUseCase
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeGemmaReaderTrial
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GemmaReaderTrialTest {

    @Test
    @DisplayName("Gemma is the default reader: every reading is Gemma's until the old reader is chosen")
    fun `gemma by default`() = runBlocking {
        val trial = FakeGemmaReaderTrial()

        assertThat(trial.isEnabled()).isTrue()
        assertThat(trial.shouldRead("d1")).isTrue()
    }

    @Test
    @DisplayName("the debug switch chooses the old reader for every reading, and choosing Gemma again ends that")
    fun `the switch`() = runBlocking {
        val trial = FakeGemmaReaderTrial()

        trial.setEnabled(false)
        assertThat(trial.shouldRead("d1")).isFalse()
        assertThat(trial.shouldRead("d2")).isFalse()

        trial.setEnabled(true)
        assertThat(trial.shouldRead("d1")).isTrue()
    }

    @Test
    @DisplayName("a one-off request makes the next reading of that document Gemma's, once, even with the old reader chosen, and does not touch the others")
    fun `one off request`() = runBlocking {
        val trial = FakeGemmaReaderTrial(enabled = false)
        trial.requestOnce("d1")

        assertThat(trial.shouldRead("d2")).isFalse()
        assertThat(trial.shouldRead("d1")).isTrue()
        assertThat(trial.shouldRead("d1")).isFalse()
    }

    @Test
    @DisplayName("the debug action asks for Gemma's reading and queues a fresh read of that document")
    fun `read again with gemma`() = runBlocking {
        val trial = FakeGemmaReaderTrial(enabled = false)
        val processor = FakeDocumentProcessor()

        ReadAgainWithGemmaUseCase(trial, processor)("d1")

        assertThat(trial.isRequested("d1")).isTrue()
        assertThat(processor.enqueueCalls).containsExactly(FakeDocumentProcessor.EnqueueCall("d1", force = true))
        // The switch itself is untouched: only that reading is Gemma's.
        assertThat(trial.isEnabled()).isFalse()
    }
}
