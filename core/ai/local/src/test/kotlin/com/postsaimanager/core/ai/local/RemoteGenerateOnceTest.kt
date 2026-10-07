package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The AIDL pass-through of `ChatEngine.generateOnce` (plan 16, A2 wired): what crosses to the `:inference` process, and the cases
 * where the call is skipped without ever reaching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGenerateOnceTest {

    private val service = mockk<IInferenceService>()
    private val mutex = Mutex()
    private val request = AiRequest(prompt = "the prompt", maxTokens = 120, temperature = 0.1f, topK = 1)

    private fun call(remote: IInferenceService? = service) =
        RemoteGenerateOnce(mutex, { remote }, UnconfinedTestDispatcher())

    @Test
    @DisplayName("the system, the prompt and the sampling cross the boundary and the answer comes back")
    fun `round trip`() = runTest {
        every { service.generateLiteRtOnce(any(), any(), any(), any(), any()) } returns "- a note"

        val answer = call()("the system", request)

        assertThat(answer).isEqualTo("- a note")
        verify(exactly = 1) { service.generateLiteRtOnce("the system", "the prompt", 120, 0.1f, 1) }
        // The shared call mutex is free again for the next caller.
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("the service's null (no model, busy, a failed generation) is the answer null")
    fun `null passes through`() = runTest {
        every { service.generateLiteRtOnce(any(), any(), any(), any(), any()) } returns null

        assertThat(call()("s", request)).isNull()
    }

    @Test
    @DisplayName("while another caller holds the model the call is skipped, not queued, and never reaches the service")
    fun `busy is skipped`() = runTest {
        mutex.lock()

        assertThat(call()("s", request)).isNull()

        verify(exactly = 0) { service.generateLiteRtOnce(any(), any(), any(), any(), any()) }
        // Still held by its owner: the skipped call did not release a lock it never took.
        assertThat(mutex.isLocked).isTrue()
    }

    @Test
    @DisplayName("no connected service is the answer null")
    fun `no service`() = runTest {
        assertThat(call(remote = null)("s", request)).isNull()
        assertThat(mutex.isLocked).isFalse()
    }

    @Test
    @DisplayName("a service that died during the call is the answer null, and the mutex is released")
    fun `remote failure`() = runTest {
        every { service.generateLiteRtOnce(any(), any(), any(), any(), any()) } throws IllegalStateException("the process died")

        assertThat(call()("s", request)).isNull()
        assertThat(mutex.isLocked).isFalse()
    }
}
