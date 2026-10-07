package com.postsaimanager.feature.chat

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModelHeaderSubtitleTest {

    private val loading = ModelLoadState.Loading("m", startedAtNanos = 0L)
    private val ready = ModelLoadState.Ready("m", InferenceConfig(contextTokens = 4096, threads = 4), 1L)
    private val failed = ModelLoadState.Failed("m", "boom")

    @Test
    @DisplayName("loading the model says so, even while the pre-warm flag is already set")
    fun `loading wins over priming`() {
        assertThat(modelHeaderSubtitle(loading, isPrimingConversation = true)).isEqualTo("Loading model…")
        assertThat(modelHeaderSubtitle(loading, isPrimingConversation = false)).isEqualTo("Loading model…")
    }

    @Test
    @DisplayName("priming a resident model says Preparing conversation")
    fun `ready and priming`() {
        assertThat(modelHeaderSubtitle(ready, isPrimingConversation = true)).isEqualTo("Preparing conversation…")
    }

    @Test
    @DisplayName("priming before the load registers is still Preparing conversation, not Not loaded")
    fun `idle and priming`() {
        assertThat(modelHeaderSubtitle(ModelLoadState.Idle, isPrimingConversation = true))
            .isEqualTo("Preparing conversation…")
    }

    @Test
    @DisplayName("idle, ready and failed fall back to their own subtitles when not priming")
    fun `not priming`() {
        assertThat(modelHeaderSubtitle(ModelLoadState.Idle, false)).isEqualTo("Not loaded")
        assertThat(modelHeaderSubtitle(ready, false)).isEqualTo("CPU · 4096 ctx")
        assertThat(modelHeaderSubtitle(failed, false)).isEqualTo("Failed to load")
    }

    @Test
    @DisplayName("the header names the backend the engine reports running, not the one the settings asked for")
    fun `running accelerator wins`() {
        val askedGpuRunsCpu = ModelLoadState.Ready(
            "m",
            InferenceConfig(contextTokens = 8192, threads = 4, accelerator = com.postsaimanager.core.model.Accelerator.GPU),
            1L,
            runningAccelerator = com.postsaimanager.core.model.Accelerator.CPU,
        )
        assertThat(modelHeaderSubtitle(askedGpuRunsCpu, false)).isEqualTo("CPU · 8192 ctx")
    }

    @Test
    @DisplayName("a failed load is never masked by the priming flag")
    fun `failed while priming`() {
        assertThat(modelHeaderSubtitle(failed, true)).isEqualTo("Failed to load")
        assertThat(showsHeaderSpinner(failed, true)).isFalse()
    }

    @Test
    @DisplayName("waiting behind a document read says so, whatever the load state")
    fun `waiting for a document`() {
        val waiting = "Waiting for a document to finish reading…"
        assertThat(modelHeaderSubtitle(ModelLoadState.Idle, false, isWaitingForDocument = true)).isEqualTo(waiting)
        assertThat(modelHeaderSubtitle(loading, true, isWaitingForDocument = true)).isEqualTo(waiting)
        assertThat(modelHeaderSubtitle(ready, true, isWaitingForDocument = true)).isEqualTo(waiting)
        assertThat(showsHeaderSpinner(ModelLoadState.Idle, false, isWaitingForDocument = true)).isTrue()
    }

    @Test
    @DisplayName("a failed load is still reported while a document is being read")
    fun `failed beats waiting`() {
        assertThat(modelHeaderSubtitle(failed, false, isWaitingForDocument = true)).isEqualTo("Failed to load")
        assertThat(showsHeaderSpinner(failed, false, isWaitingForDocument = true)).isFalse()
    }

    @Test
    @DisplayName("ui state is waiting when the pre-warm found the engine busy or a send carries the busy reason")
    fun `ui state waiting flag`() {
        assertThat(ChatUiState().isWaitingForDocument).isFalse()
        assertThat(ChatUiState(primeWaitingForDocument = true).isWaitingForDocument).isTrue()
        assertThat(
            ChatUiState(
                isProcessing = true,
                statusText = com.postsaimanager.core.domain.usecase.ChatTurn.PreparingModel.WAITING_FOR_DOCUMENT,
            ).isWaitingForDocument,
        ).isTrue()
        assertThat(ChatUiState(isProcessing = true, statusText = "Loading model…").isWaitingForDocument).isFalse()
    }

    @Test
    @DisplayName("the spinner tracks the subtitle: loading or priming only")
    fun `spinner`() {
        assertThat(showsHeaderSpinner(loading, false)).isTrue()
        assertThat(showsHeaderSpinner(ready, true)).isTrue()
        assertThat(showsHeaderSpinner(ready, false)).isFalse()
        assertThat(showsHeaderSpinner(ModelLoadState.Idle, false)).isFalse()
    }
}
