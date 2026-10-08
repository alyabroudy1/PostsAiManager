package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.StructuredRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The AIDL pass-through of `ChatEngine.generateStructured` ("Gemma reads the letter"): what crosses to the `:inference` process (the
 * schema and the picture paths, never picture bytes) and the cases where the call is skipped without ever reaching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGenerateStructuredTest {

    private val service = mockk<IInferenceService>()
    private val mutex = Mutex()
    private val request = StructuredRequest(
        system = "the system", prompt = "the prompt", schema = "{\"type\":\"object\"}", imagePaths = listOf("/files/p1.jpg"),
        maxTokens = 900, temperature = 0.1f, topK = 20, timeoutMs = 120_000L,
    )

    private fun call(remote: IInferenceService? = service) = RemoteGenerateStructured(
        mutex, { remote }, UnconfinedTestDispatcher(),
        newLead = { onText ->
            mockk<ILeadCallback>().also { every { it.onLead(any()) } answers { onText(firstArg()) } }
        },
    )

    private fun io.mockk.MockKMatcherScope.anyCall() =
        service.generateLiteRtStructured(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())

    @Test
    @DisplayName("the texts, the schema, the picture paths and the sampling cross the boundary and the answer comes back")
    fun `round trip`() = runTest {
        every { anyCall() } returns "{\"category\":\"bill\"}"

        val answer = call()(request)

        assertThat(answer).isEqualTo("{\"category\":\"bill\"}")
        verify(exactly = 1) {
            service.generateLiteRtStructured(
                "the system", "the prompt", "{\"type\":\"object\"}", match { it.toList() == listOf("/files/p1.jpg") }, 900, 0.1f, 20, 120_000L, null, null, null,
            )
        }
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("with a lead prompt the first turn's text comes back over the callback while the call runs, and reaches the request's listener")
    fun `lead text is handed on`() = runTest {
        val heard = mutableListOf<String>()
        val led = request.copy(leadPrompt = "the lead", onLead = { heard += it })
        val callback = slot<ILeadCallback>()
        every {
            service.generateLiteRtStructured(any(), any(), any(), any(), any(), any(), any(), any(), "the lead", capture(callback), any())
        } answers {
            callback.captured.onLead("A short summary.")
            "{\"c\":1}"
        }

        val answer = call()(led)

        assertThat(answer).isEqualTo("{\"c\":1}")
        assertThat(heard).containsExactly("A short summary.")
    }

    @Test
    @DisplayName("without a listener no callback is sent, even when a lead prompt is set")
    fun `no listener no callback`() = runTest {
        every { anyCall() } returns "{}"

        call()(request.copy(leadPrompt = "the lead"))

        verify { service.generateLiteRtStructured(any(), any(), any(), any(), any(), any(), any(), any(), "the lead", null, null) }
    }

    @Test
    @DisplayName("the service's null (no model, busy, a failed or timed-out generation) is the answer null")
    fun `null passes through`() = runTest {
        every { anyCall() } returns null

        assertThat(call()(request)).isNull()
    }

    @Test
    @DisplayName("while another caller holds the model the call is skipped, not queued, and never reaches the service")
    fun `busy is skipped`() = runTest {
        mutex.lock()

        assertThat(call()(request)).isNull()

        verify(exactly = 0) { anyCall() }
        assertThat(mutex.isLocked).isTrue()
    }

    @Test
    @DisplayName("no connected service is the answer null")
    fun `no service`() = runTest {
        assertThat(call(remote = null)(request)).isNull()
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("a service that died during the call is the answer null, and the mutex is released")
    fun `remote failure`() = runTest {
        every { anyCall() } throws IllegalStateException("the process died")

        assertThat(call()(request)).isNull()
        assertThat(mutex.isLocked).isFalse()
    }
}
