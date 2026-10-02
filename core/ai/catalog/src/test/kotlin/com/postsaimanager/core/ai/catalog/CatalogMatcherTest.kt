package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import org.junit.jupiter.api.Test

/** A side-loaded GGUF that is a catalog file is recognised by its hash and size, at import and for imports made before. */
class CatalogMatcherTest {

    private val twoB = BundledCatalog.models.first { it.id == "qwen3.5-2b-q4_k_m" }

    private fun imported(sha256: String, sizeBytes: Long, descriptorId: String? = null) = InstalledModel(
        id = "imported-Qwen3.5-2B-Q4_K_M", descriptorId = descriptorId, name = "Qwen3.5-2B-Q4_K_M", filePath = "/models/Qwen3.5-2B-Q4_K_M.gguf",
        sizeBytes = sizeBytes, sha256 = sha256, contextTokens = 4096, source = ModelSource.IMPORTED, installedAt = 0L,
    )

    @Test
    fun `a file with the catalog hash and size is that catalog model, whatever it is called`() {
        assertThat(CatalogMatcher.descriptorFor(twoB.sha256!!, twoB.sizeBytes)?.id).isEqualTo(twoB.id)
        // The hash is compared case-insensitively (a tool may print it in capitals).
        assertThat(CatalogMatcher.descriptorFor(twoB.sha256!!.uppercase(), twoB.sizeBytes)?.id).isEqualTo(twoB.id)
    }

    @Test
    fun `the same hash with another size, or another hash, is not a catalog model`() {
        assertThat(CatalogMatcher.descriptorFor(twoB.sha256!!, twoB.sizeBytes + 1)).isNull()
        assertThat(CatalogMatcher.descriptorFor("0".repeat(64), twoB.sizeBytes)).isNull()
    }

    @Test
    fun `an import of a catalog file gets the descriptor, so the form model is recognised`() {
        val adopted = CatalogMatcher.adopt(imported(twoB.sha256!!, twoB.sizeBytes))

        assertThat(adopted.descriptorId).isEqualTo(twoB.id)
        assertThat(adopted.source).isEqualTo(ModelSource.IMPORTED)
    }

    @Test
    fun `a model with a descriptor, or one that matches nothing, is left as it is`() {
        val owned = imported("x", 1L, descriptorId = "something")
        val unknown = imported("0".repeat(64), 5L)

        assertThat(CatalogMatcher.adopt(owned)).isEqualTo(owned)
        assertThat(CatalogMatcher.adopt(unknown)).isEqualTo(unknown)
    }

    @Test
    fun `the startup migration ties earlier imports to their catalog entry and keeps the rest`() {
        val index = InstalledIndex(
            models = listOf(imported(twoB.sha256!!, twoB.sizeBytes), imported("0".repeat(64), 5L).copy(id = "imported-mine")),
            activeModelId = "imported-mine",
        )

        val migrated = index.withCatalogMatches()

        assertThat(migrated.models.map { it.descriptorId }).containsExactly(twoB.id, null).inOrder()
        assertThat(migrated.activeModelId).isEqualTo("imported-mine")
    }
}
