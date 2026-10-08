package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.InferenceConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chat session's bookkeeping: what is primed, and the repair of a session a reading took over between `ensureChatSession` and
 * the send. The service is a mock, the model a pair the test moves.
 */
class LlamaChatSessionTest {

    private val service = mockk<IInferenceService>(relaxed = true)
    private val config = InferenceConfig(contextTokens = 4096, threads = 4)
    private var resident: Pair<String, InferenceConfig>? = "/models/chat.gguf" to config
    private val loads = mutableListOf<String>()
    private var loadResult: PamResult<Unit> = PamResult.Success(Unit)

    private val session = LlamaChatSession(
        remote = { service },
        residentModel = { resident },
        loadModel = { path, _ ->
            loads += path
            if (loadResult is PamResult.Success) resident = path to config
            loadResult
        },
    )

    private val history = listOf(
        AiChatMessage(AiChatRole.USER, "first question"),
        AiChatMessage(AiChatRole.ASSISTANT, "first answer"),
    )

    init {
        every { service.openChatSession(any()) } returns true
        every { service.primeChatSession(any(), any()) } returns true
    }

    @Test
    @DisplayName("priming opens the session, replays the history once, and says the conversation is primed")
    fun `prime`() = runTest {
        val primed = session.primeLocked("c1", "system", history)

        assertThat(primed).isTrue()
        assertThat(session.isPrimed("c1")).isTrue()
        assertThat(session.isPrimed("c2")).isFalse()
        verifyOrder {
            service.openChatSession("system")
            service.primeChatSession(arrayOf("user", "assistant"), arrayOf("first question", "first answer"))
        }
    }

    @Test
    @DisplayName("a second prime of the same conversation is a no-op")
    fun `prime is idempotent`() = runTest {
        session.primeLocked("c1", "system", history)

        assertThat(session.primeLocked("c1", "system", history)).isFalse()
        verify(exactly = 1) { service.openChatSession(any()) }
    }

    @Test
    @DisplayName("the session is still this conversation's: the send changes nothing and primes nothing again")
    fun `still ours`() = runTest {
        session.primeLocked("c1", "system", history)

        assertThat(session.ensureStillOursLocked("next question")).isTrue()

        verify(exactly = 1) { service.openChatSession(any()) }
    }

    @Test
    @DisplayName("a reading took the cache over after ensureChatSession: the send primes the session again, from what it held, and does not fail")
    fun `taken over is primed again`() = runTest {
        session.primeLocked("c1", "system", history)
        // A reading's open() (or a one-shot generation) takes the KV cache over in the gap before the send.
        session.invalidate()

        val ok = session.ensureStillOursLocked("next question")

        assertThat(ok).isTrue()
        assertThat(session.isPrimed("c1")).isTrue()
        verify(exactly = 2) { service.openChatSession("system") }
        verify(exactly = 2) { service.primeChatSession(arrayOf("user", "assistant"), arrayOf("first question", "first answer")) }
        assertThat(loads).isEmpty()
    }

    @Test
    @DisplayName("the turns committed since the prime come back with it, a discarded one does not")
    fun `committed turns are replayed`() = runTest {
        session.primeLocked("c1", "system", history)
        session.ensureStillOursLocked("second question")
        session.onCommitted("second answer")
        session.ensureStillOursLocked("a stopped question")
        session.onDiscarded()

        session.invalidate()
        session.ensureStillOursLocked("third question")

        verify {
            service.primeChatSession(
                arrayOf("user", "assistant", "user", "assistant"),
                arrayOf("first question", "first answer", "second question", "second answer"),
            )
        }
    }

    @Test
    @DisplayName("a reading replaced the chat model: it is loaded again before the session is primed on it")
    fun `replaced model is brought back`() = runTest {
        session.primeLocked("c1", "system", history)
        resident = "/models/reader.gguf" to config
        session.invalidate()

        val ok = session.ensureStillOursLocked("next question")

        assertThat(ok).isTrue()
        assertThat(loads).containsExactly("/models/chat.gguf")
        assertThat(session.isPrimed("c1")).isTrue()
    }

    @Test
    @DisplayName("when the chat model cannot be brought back the send is told, rather than sending into the wrong model")
    fun `restore failure is reported`() = runTest {
        session.primeLocked("c1", "system", history)
        resident = "/models/reader.gguf" to config
        session.invalidate()
        loadResult = PamResult.Error(PamError.ModelNotLoaded("gone"))

        assertThat(session.ensureStillOursLocked("next question")).isFalse()
    }

    @Test
    @DisplayName("a session that was reset or cleared is not restored: the send goes on as it always did")
    fun `cleared session is not restored`() = runTest {
        session.primeLocked("c1", "system", history)
        session.clear()

        assertThat(session.ensureStillOursLocked("next question")).isTrue()

        verify(exactly = 1) { service.openChatSession(any()) }
        assertThat(session.isPrimed("c1")).isFalse()
    }

    @Test
    @DisplayName("a prime that failed for another conversation does not leave the previous one to be restored into its send")
    fun `failed prime forgets the old plan`() = runTest {
        session.primeLocked("c1", "system", history)
        every { service.openChatSession(any()) } returns false

        assertThat(session.primeLocked("c2", "system", emptyList())).isFalse()
        session.invalidate()

        assertThat(session.ensureStillOursLocked("question for c2")).isTrue()
        // c1's history was primed once; c2's failed open never replays anything, and c1's plan was not restored for c2's send.
        verify(exactly = 1) { service.primeChatSession(any(), any()) }
        verify(exactly = 2) { service.openChatSession(any()) }
    }
}
