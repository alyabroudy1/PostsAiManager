package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatActivityGate
import com.postsaimanager.core.domain.ai.ReadingModel
import com.postsaimanager.core.model.InferenceConfig
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReaderModelGuardTest {

    private val config = InferenceConfig(contextTokens = 4096, threads = 4)
    private val reading = ReadingModel("/models/reader.gguf", config)

    private var resident: String? = "/models/reader.gguf"
    private val loads = mutableListOf<String>()
    private var loadResult: PamResult<Unit> = PamResult.Success(Unit)
    private var chatActive = false

    private val gate = object : ChatActivityGate {
        override fun isChatActive() = chatActive
    }

    private fun guard(maxWaitMs: Long = 10_000) = ReaderModelGuard(
        chatGate = gate,
        isResident = { it == resident },
        load = { path, _ ->
            loads += path
            if (loadResult is PamResult.Success) resident = path
            loadResult
        },
        maxWaitMs = maxWaitMs,
    )

    @Test
    @DisplayName("the reader is resident: nothing is loaded")
    fun `reader resident is left alone`() = runTest {
        assertThat(guard().open(reading)).isEqualTo(PamResult.Success(Unit))
        assertThat(guard().reopen(reading)).isEqualTo(PamResult.Success(Unit))
        assertThat(loads).isEmpty()
    }

    @Test
    @DisplayName("a run that names no reader (form filling, a chat) is never redirected")
    fun `no reading model no check`() = runTest {
        resident = "/models/chat.gguf"

        assertThat(guard().open(null)).isEqualTo(PamResult.Success(Unit))
        assertThat(guard().reopen(null)).isEqualTo(PamResult.Success(Unit))
        assertThat(loads).isEmpty()
    }

    @Test
    @DisplayName("a chat-model load replaced the reader mid-document: the reader is loaded again, never scored on the chat model")
    fun `replaced reader is loaded again`() = runTest {
        resident = "/models/chat.gguf"

        val result = guard().open(reading)

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(loads).containsExactly("/models/reader.gguf")
        assertThat(resident).isEqualTo("/models/reader.gguf")
    }

    @Test
    @DisplayName("an active chat is waited for outside the lock, then the reader is loaded")
    fun `waits for the chat to be idle`() = runTest {
        resident = "/models/chat.gguf"
        chatActive = true
        val idleAt = 20_000L
        val timed = object : ChatActivityGate {
            override fun isChatActive() = currentTime < idleAt
        }
        val waiting = ReaderModelGuard(timed, { it == resident }, { path, _ -> loads += path; resident = path; PamResult.Success(Unit) }, 60_000)

        val result = waiting.open(reading)

        assertThat(result).isEqualTo(PamResult.Success(Unit))
        assertThat(currentTime).isAtLeast(idleAt)
        assertThat(loads).containsExactly("/models/reader.gguf")
    }

    @Test
    @DisplayName("a chat that never goes idle: the reading is refused (to be run again later), the chat model is not evicted and nothing scores on it")
    fun `busy chat is not evicted`() = runTest {
        resident = "/models/chat.gguf"
        chatActive = true

        val opened = guard(maxWaitMs = 10_000).open(reading)
        val reopened = guard().reopen(reading)

        assertThat(opened).isInstanceOf(PamResult.Error::class.java)
        assertThat(reopened).isInstanceOf(PamResult.Error::class.java)
        assertThat(loads).isEmpty()
        assertThat(resident).isEqualTo("/models/chat.gguf")
    }

    @Test
    @DisplayName("a reader that fails to load is an error, not a silent fall back to whatever is resident")
    fun `failed reader load is an error`() = runTest {
        resident = "/models/chat.gguf"
        loadResult = PamResult.Error(PamError.ModelNotLoaded("could not load"))

        val result = guard().open(reading)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
    }
}
