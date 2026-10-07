package com.postsaimanager.core.ai.catalog

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelSource
import org.junit.jupiter.api.Test

class InstalledModelSummaryTest {

    private fun installed(descriptorId: String?, name: String) = InstalledModel(
        id = "m1", descriptorId = descriptorId, name = name, filePath = "/models/m1", sizeBytes = 1L, sha256 = "x",
        contextTokens = 8192, source = ModelSource.CATALOG, installedAt = 0L,
    )

    @Test
    fun `a catalogue install shows the catalogue's current name, not the stored old one`() {
        val summary = summaryOf(installed("gemma-4-e2b-it-litertlm", "Gemma 4 E2B · fast (GPU)"))

        assertThat(summary.name).isEqualTo("Gemma 4 E2B")
        assertThat(summary.quantization).isEqualTo("LiteRT-LM")
    }

    @Test
    fun `a model the catalogue does not know keeps its stored name`() {
        assertThat(summaryOf(installed(null, "My own model")).name).isEqualTo("My own model")
        assertThat(summaryOf(installed("gone-from-catalogue", "Old entry")).name).isEqualTo("Old entry")
    }
}
