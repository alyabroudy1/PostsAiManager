package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [InstalledIndex]'s resolution rules.
 *
 * Every rule here is a fallback, and each one decides whether a scanned document gets read
 * at all. The failure they prevent is silent: a user uninstalls a model, and documents stop
 * being understood with nothing to say why.
 */
class InstalledIndexTest {

    private fun model(id: String) = InstalledModel(
        id = id,
        descriptorId = id,
        name = id,
        filePath = "/models/$id.gguf",
        sizeBytes = 1L,
        sha256 = "x",
        contextTokens = 4096,
        source = ModelSource.CATALOG,
        installedAt = 0L,
    )

    private val chat = model("qwen")
    private val reader = model("gemma")

    @Test
    @DisplayName("one model does both jobs unless the user splits them")
    fun `reading defaults to the chat model`() {
        val index = InstalledIndex(models = listOf(chat), activeModelId = "qwen")

        assertThat(index.readerModel()?.id).isEqualTo("qwen")
        // Matters beyond tidiness: the engine holds one model at a time, so sharing means
        // one load rather than a reload every time the app alternates.
        assertThat(index.sharesOneModel).isTrue()
    }

    @Test
    fun `a chosen reader is used for reading and not for chat`() {
        val index = InstalledIndex(
            models = listOf(chat, reader),
            activeModelId = "qwen",
            extractionModelId = "gemma",
        )

        assertThat(index.chatModel()?.id).isEqualTo("qwen")
        assertThat(index.readerModel()?.id).isEqualTo("gemma")
        assertThat(index.sharesOneModel).isFalse()
    }

    @Test
    @DisplayName("a reader that is no longer installed falls back rather than disappearing")
    fun `reading survives the chosen model being removed`() {
        val index = InstalledIndex(
            models = listOf(chat),
            activeModelId = "qwen",
            // Points at a model that is gone — what an uninstall leaves behind if the index
            // is read before reconcile() runs.
            extractionModelId = "gemma",
        )

        // Reading with a different model than the user picked beats not reading at all.
        assertThat(index.readerModel()?.id).isEqualTo("qwen")
    }

    @Test
    fun `naming the chat model as the reader still counts as sharing`() {
        val index = InstalledIndex(
            models = listOf(chat),
            activeModelId = "qwen",
            extractionModelId = "qwen",
        )

        assertThat(index.sharesOneModel).isTrue()
    }

    @Test
    fun `an empty index resolves to nothing rather than throwing`() {
        val index = InstalledIndex()

        assertThat(index.chatModel()).isNull()
        assertThat(index.readerModel()).isNull()
    }
}
