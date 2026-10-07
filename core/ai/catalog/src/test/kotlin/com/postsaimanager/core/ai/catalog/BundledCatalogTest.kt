package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.model.ModelRuntime
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the shape of the pinned catalog.
 *
 * These cannot say whether a hash is *correct* — only a download can, and
 * `ModelCatalogDownloadTest` does that on device. What they catch is the pin being quietly
 * weakened: a URL edited to a branch, a hash truncated in a merge, an entry added without
 * one. Each of those turns a fail-closed integrity check into a check that passes on the
 * wrong bytes, and none is visible in review.
 */
class BundledCatalogTest {

    @Test
    @DisplayName("every catalogue model has a reading profile, so none silently falls back to the single call")
    fun `every catalogue model id has a model profile`() {
        // Only a model that can read documents is ever read with; a chat-only runtime's model has no reading profile.
        BundledCatalog.models.filter { it.runtime.canReadDocuments }.forEach { model ->
            assertThat(ModelProfiles.isKnown(model.id)).isTrue()
            assertThat(ModelProfiles.of(model.id).modelId).isEqualTo(model.id)
        }
    }

    @Test
    @DisplayName("Gemma 4 is listed under its real licence, Apache 2.0, not the older Gemma terms of use")
    fun `gemma 4 entries are apache licensed`() {
        val gemma4 = BundledCatalog.models.filter { it.family == "Gemma" }
        assertThat(gemma4).isNotEmpty()
        gemma4.forEach { assertThat(it.license).isEqualTo("Apache-2.0") }
    }

    @Test
    @DisplayName("every model can actually be installed")
    fun `all entries carry a url and a hash`() {
        // The state this replaced: every entry was NotInstallable, so the model manager
        // listed models that could never be downloaded.
        BundledCatalog.models.forEach { model ->
            assertThat(model.isInstallable).isTrue()
        }
        assertThat(BundledCatalog.models).isNotEmpty()
    }

    @Test
    @DisplayName("the reader is the installable Qwen3.5 0.8B, and the only one")
    fun `reader model is the small default`() {
        val model = BundledCatalog.readerModel
        assertThat(model.name).isEqualTo("Qwen3.5 0.8B")
        assertThat(model.id).isEqualTo(BundledCatalog.READER_MODEL_ID)
        assertThat(model.quantization).isEqualTo("Q4_K_M")
        assertThat(model.isInstallable).isTrue()
        assertThat(BundledCatalog.models.count { it.role == com.postsaimanager.core.model.ModelRole.READER_AND_CHAT }).isEqualTo(1)
    }

    @Test
    @DisplayName("every model carries memory thresholds that order sensibly")
    fun `memory thresholds are data and consistent`() {
        BundledCatalog.models.forEach { model ->
            assertThat(model.minRamGb).isGreaterThan(0.0)
            assertThat(model.recommendedRamGb).isAtLeast(model.minRamGb)
            assertThat(model.approxRamUseGb).isGreaterThan(0.0)
        }
    }

    @Test
    @DisplayName("only the 0.8B and 2B may be the default, and the slower models say so")
    fun `preselectable models and speed hints`() {
        val preselectable = BundledCatalog.models.filter { it.preselectable }.map { it.id }
        assertThat(preselectable).containsExactly("qwen3.5-0.8b-q4_k_m", "qwen3.5-2b-q4_k_m")
        // The speed note compares with the llama.cpp reader; a model on another runtime is not slower than it for the same reason.
        BundledCatalog.models.filter { it.runtime == ModelRuntime.LLAMA_CPP }.filterNot { it.preselectable }.forEach {
            assertThat(it.speedHint).isEqualTo(com.postsaimanager.core.model.SpeedHint.MUCH_SLOWER)
        }
    }

    @Test
    @DisplayName("Gemma 4 on LiteRT-LM: pinned to the Gallery allowlist's revisions, with the Hugging Face hash and size, chat only")
    fun `litert entries carry every pinned field`() {
        val litert = BundledCatalog.models.filter { it.runtime == ModelRuntime.LITERT_LM }
        assertThat(litert.map { it.id }).containsExactly("gemma-4-e2b-it-litertlm", "gemma-4-e4b-it-litertlm")

        val e2b = litert.first { it.parameterCount == "E2B" }
        assertThat(e2b.downloadUrl).isEqualTo(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        )
        assertThat(e2b.sha256).isEqualTo("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
        assertThat(e2b.sizeBytes).isEqualTo(2_588_147_712L)
        assertThat(e2b.minRamGb).isEqualTo(8.0)

        val e4b = litert.first { it.parameterCount == "E4B" }
        assertThat(e4b.downloadUrl).isEqualTo(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
                "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm",
        )
        assertThat(e4b.sha256).isEqualTo("0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0")
        assertThat(e4b.sizeBytes).isEqualTo(3_659_530_240L)
        assertThat(e4b.minRamGb).isEqualTo(12.0)

        litert.forEach { model ->
            assertThat(model.isInstallable).isTrue()
            assertThat(model.downloadUrl).endsWith(".litertlm")
            assertThat(model.license).isEqualTo("Apache-2.0")
            // The CPU first (the GPU engine garbled tool-call values on the test phone), the GPU still on offer.
            assertThat(model.backendSpec.accelerators)
                .containsExactly(com.postsaimanager.core.model.Accelerator.CPU, com.postsaimanager.core.model.Accelerator.GPU)
                .inOrder()
            // It only chats: it is never the model that reads letters.
            assertThat(model.role).isEqualTo(com.postsaimanager.core.model.ModelRole.CHAT)
            assertThat(model.recommendedForExtraction).isFalse()
            assertThat(model.runtime.canReadDocuments).isFalse()
        }
    }

    @Test
    @DisplayName("the GGUF Gemma 4 builds stay, next to the LiteRT-LM ones, on llama.cpp")
    fun `gguf gemma entries are kept`() {
        val gguf = BundledCatalog.models.filter { it.family == "Gemma" && it.runtime == ModelRuntime.LLAMA_CPP }
        assertThat(gguf.map { it.id }).containsExactly("gemma-4-e2b-it-qat-q4_0", "gemma-4-e4b-it-qat-q4_0")
        gguf.forEach { assertThat(it.downloadUrl).endsWith(".gguf") }
    }

    @Test
    @DisplayName("URLs name an immutable revision, never a branch")
    fun `urls are pinned`() {
        BundledCatalog.models.forEach { model ->
            val url = model.downloadUrl!!
            // A branch would let the bytes change under a fixed hash, and the resulting
            // failure looks like a network problem rather than a pinning mistake.
            assertThat(url).doesNotContain("/resolve/main/")
            assertThat(url).startsWith("https://")
            // 40-hex commit between /resolve/ and the filename.
            assertThat(url).matches(".*/resolve/[0-9a-f]{40}/.*")
        }
    }

    @Test
    fun `hashes are full sha256 and distinct`() {
        val hashes = BundledCatalog.models.map { it.sha256!! }
        hashes.forEach { assertThat(it).matches("[0-9a-f]{64}") }
        // A copy-paste that reused a hash would make one model permanently unverifiable.
        assertThat(hashes).containsNoDuplicates()
    }

    @Test
    fun `ids and download urls are unique`() {
        assertThat(BundledCatalog.models.map { it.id }).containsNoDuplicates()
        assertThat(BundledCatalog.models.map { it.downloadUrl }).containsNoDuplicates()
    }

    @Test
    @DisplayName("sizes are real byte counts, not rounded guesses")
    fun `sizes are exact`() {
        BundledCatalog.models.forEach { model ->
            assertThat(model.sizeBytes).isGreaterThan(100L * 1024 * 1024)
            // Rounded values (400 MB exactly) mean someone estimated rather than reading
            // the file listing, and the downloader uses this to report progress.
            assertThat(model.sizeBytes % 1_000_000L).isNotEqualTo(0L)
        }
    }

    @Test
    @DisplayName("memory needed always exceeds the file size")
    fun `ram estimates leave room for the runtime`() {
        BundledCatalog.models.forEach { model ->
            // Weights are not the whole cost — the KV cache and the runtime sit on top. An
            // estimate below the file size would let a device accept a model it cannot load.
            assertThat(model.minAvailableRamBytes).isGreaterThan(model.sizeBytes)
        }
    }

    @Test
    @DisplayName("a model is recommended for reading documents")
    fun `at least one extraction model is offered`() {
        val readers = BundledCatalog.models.filter { it.recommendedForExtraction }

        // Without one, a user has no signal that reading and chatting want different
        // models, and the app quietly reads letters with whatever they picked for chat.
        assertThat(readers).isNotEmpty()
        assertThat(readers.map { it.family }).contains("Gemma")
    }

    @Test
    fun `the range spans small and capable devices`() {
        val sizes = BundledCatalog.models.map { it.sizeBytes }
        // Only offering large models makes the app useless on a cheap phone; only small
        // ones caps how well it can ever read a letter.
        assertThat(sizes.min()).isLessThan(600L * 1024 * 1024)
        assertThat(sizes.max()).isGreaterThan(2L * 1024 * 1024 * 1024)
    }
}
