package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.ModelSource
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The engine-switch policy in the model index: a LiteRT-LM model chats, and never reads letters. Reading needs llama.cpp (token
 * scoring, KV prefix reuse), so whatever the user picked for chat, the reader stays a llama.cpp model.
 */
class InstalledIndexRuntimeTest {

    private fun model(id: String, runtime: ModelRuntime, descriptorId: String? = id) = InstalledModel(
        id = id,
        descriptorId = descriptorId,
        name = id,
        filePath = "/models/$id.${runtime.fileExtension}",
        sizeBytes = 1L,
        sha256 = "x",
        contextTokens = 4096,
        source = ModelSource.CATALOG,
        installedAt = 0L,
        runtime = runtime,
    )

    private val reader = model(BundledCatalog.READER_MODEL_ID, ModelRuntime.LLAMA_CPP)
    private val gemmaLiteRt = model("gemma-4-e2b-it-litertlm", ModelRuntime.LITERT_LM)
    private val gemmaGguf = model("gemma-4-e2b-it-qat-q4_0", ModelRuntime.LLAMA_CPP)

    @Test
    @DisplayName("chatting with a LiteRT-LM model leaves the reading to the llama.cpp reader")
    fun `litert chat model does not read`() {
        val index = InstalledIndex(models = listOf(reader, gemmaLiteRt), activeModelId = gemmaLiteRt.id)

        assertThat(index.chatModel()?.id).isEqualTo(gemmaLiteRt.id)
        assertThat(index.readerModel()?.id).isEqualTo(reader.id)
        // Two models, two jobs: switching between them is an engine switch.
        assertThat(index.sharesOneModel).isFalse()
    }

    @Test
    @DisplayName("a LiteRT-LM model set as the reading model is ignored: it cannot read")
    fun `litert model chosen for reading is skipped`() {
        val index = InstalledIndex(models = listOf(reader, gemmaLiteRt), activeModelId = reader.id, extractionModelId = gemmaLiteRt.id)

        assertThat(index.readerModel()?.id).isEqualTo(reader.id)
    }

    @Test
    @DisplayName("with no reader installed, a LiteRT-LM chat model is not used for reading: nothing reads rather than a model that cannot")
    fun `litert chat model is never the fallback reader`() {
        val index = InstalledIndex(models = listOf(gemmaLiteRt), activeModelId = gemmaLiteRt.id)

        assertThat(index.readerModel()).isNull()
    }

    @Test
    @DisplayName("a GGUF Gemma beside the LiteRT-LM one can be the chosen reader as before")
    fun `gguf gemma still reads`() {
        val index = InstalledIndex(
            models = listOf(reader, gemmaLiteRt, gemmaGguf),
            activeModelId = gemmaLiteRt.id,
            extractionModelId = gemmaGguf.id,
        )

        assertThat(index.readerModel()?.id).isEqualTo(gemmaGguf.id)
    }

    @Test
    @DisplayName("an index written before LiteRT-LM existed reads, every model on llama.cpp")
    fun `old index has llama runtime`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"models":[{"id":"a","name":"a","filePath":"/m/a.gguf","sizeBytes":1,"sha256":"x","contextTokens":4096,
            |"source":"CATALOG","installedAt":0}],"activeModelId":"a"}""".trimMargin()

        val index = json.decodeFromString<InstalledIndex>(old)

        assertThat(index.models.single().runtime).isEqualTo(ModelRuntime.LLAMA_CPP)
    }

    @Test
    @DisplayName("an imported file that is the catalogue's LiteRT-LM model (same hash and size) takes its descriptor and runtime")
    fun `matcher adopts the runtime`() {
        val descriptor = BundledCatalog.models.first { it.id == "gemma-4-e2b-it-litertlm" }
        val imported = model("imported-gemma", ModelRuntime.LLAMA_CPP, descriptorId = null)
            .copy(sha256 = descriptor.sha256!!, sizeBytes = descriptor.sizeBytes)

        val adopted = CatalogMatcher.adopt(imported)

        assertThat(adopted.descriptorId).isEqualTo(descriptor.id)
        assertThat(adopted.runtime).isEqualTo(ModelRuntime.LITERT_LM)
    }
}
