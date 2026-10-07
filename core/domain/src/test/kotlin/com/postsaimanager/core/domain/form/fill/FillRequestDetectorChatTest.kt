package com.postsaimanager.core.domain.form.fill

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.form.FakeEmbedder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fill-request check must not replace the chat model while the chat is in use: it was the job that evicted Gemma between two
 * chat messages (the next message then reloaded it and read the whole conversation again).
 */
class FillRequestDetectorChatTest {

    private class CountingModel(private val score: Double = 1.0) : FormModel {
        var calls = 0
            private set

        override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> {
            calls++
            return PamResult.Success(statements.map { score })
        }
    }

    private class Gate(var active: Boolean) : ChatActivityGate {
        override fun isChatActive() = active
    }

    // An embedder that is not ready lets every message through the cheap gate, so only the chat activity decides.
    private fun detector(model: FormModel, gate: ChatActivityGate) = FillRequestDetector(model, FakeEmbedder(ready = false), chatActivity = gate)

    @Test
    @DisplayName("on a document that is not a form, an active chat is never interrupted by the model")
    fun `an active chat is left alone`() = runTest {
        val model = CountingModel()

        val asks = detector(model, Gate(active = true)).asksForFill("What is the amount due?", documentIsForm = false)

        assertThat(asks).isFalse()
        assertThat(model.calls).isEqualTo(0)
    }

    @Test
    @DisplayName("with the chat idle the model is asked, as before")
    fun `an idle chat lets the model decide`() = runTest {
        val model = CountingModel()

        val asks = detector(model, Gate(active = false)).asksForFill("Help me fill in this form", documentIsForm = false)

        assertThat(asks).isTrue()
        assertThat(model.calls).isEqualTo(1)
    }

    @Test
    @DisplayName("on a form, the request is still read while the chat is active: the person asked for the form agent")
    fun `a form is still read`() = runTest {
        val model = CountingModel()

        val asks = detector(model, Gate(active = true)).asksForFill("Fill it in please", documentIsForm = true)

        assertThat(asks).isTrue()
        assertThat(model.calls).isEqualTo(1)
    }
}
