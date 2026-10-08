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
    @DisplayName("the trial is off by default: no reading is Gemma's")
    fun `off by default`() = runBlocking {
        val trial = FakeGemmaReaderTrial()

        assertThat(trial.isEnabled()).isFalse()
        assertThat(trial.shouldRead("d1")).isFalse()
    }

    @Test
    @DisplayName("the switch makes every reading Gemma's, and turning it off ends that")
    fun `the switch`() = runBlocking {
        val trial = FakeGemmaReaderTrial()

        trial.setEnabled(true)
        assertThat(trial.shouldRead("d1")).isTrue()
        assertThat(trial.shouldRead("d2")).isTrue()

        trial.setEnabled(false)
        assertThat(trial.shouldRead("d1")).isFalse()
    }

    @Test
    @DisplayName("a one-off request makes the next reading of that document Gemma's, once, and does not touch the others")
    fun `one off request`() = runBlocking {
        val trial = FakeGemmaReaderTrial()
        trial.requestOnce("d1")

        assertThat(trial.shouldRead("d2")).isFalse()
        assertThat(trial.shouldRead("d1")).isTrue()
        assertThat(trial.shouldRead("d1")).isFalse()
    }

    @Test
    @DisplayName("the debug action asks for Gemma's reading and queues a fresh read of that document")
    fun `read again with gemma`() = runBlocking {
        val trial = FakeGemmaReaderTrial()
        val processor = FakeDocumentProcessor()

        ReadAgainWithGemmaUseCase(trial, processor)("d1")

        assertThat(trial.isRequested("d1")).isTrue()
        assertThat(processor.enqueueCalls).containsExactly(FakeDocumentProcessor.EnqueueCall("d1", force = true))
        // The switch itself stays off: only that reading is Gemma's.
        assertThat(trial.isEnabled()).isFalse()
    }
}
