package com.postsaimanager.setup

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.ai.catalog.BundledCatalog
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.ModelCatalogRepository
import com.postsaimanager.core.ai.catalog.ModelCatalogState
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.EmbeddingModelManager
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.domain.setup.DeviceCapabilities
import com.postsaimanager.core.domain.setup.RecommendChatModelUseCase
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.DeviceProfile
import com.postsaimanager.core.model.InstalledModel
import com.postsaimanager.core.model.ModelFit
import com.postsaimanager.core.model.ModelSource
import com.postsaimanager.core.model.SetupPartStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** First-run setup with Gemma chosen: one model plus the search model, and setup completes without the Qwen reader. */
class CatalogModelSetupGatewayTest {

    private val gemma = BundledCatalog.models.first { it.id == "gemma-4-e2b-it-litertlm" }
    private val reader = BundledCatalog.readerModel
    private val qwen2b = BundledCatalog.models.first { it.id == "qwen3.5-2b-q4_k_m" }

    private val capability = DeviceCapability(
        totalRamBytes = 12L shl 30, availableRamBytes = 8L shl 30, freeStorageBytes = 50L shl 30, supportedAbis = listOf("arm64-v8a"),
    )

    private fun installed(model: AiModelDescriptor) = InstalledModel(
        id = model.id, descriptorId = model.id, name = model.name, filePath = "/m/${model.id}", sizeBytes = model.sizeBytes,
        sha256 = model.sha256.orEmpty(), contextTokens = 4096, source = ModelSource.CATALOG, installedAt = 0L, runtime = model.runtime,
    )

    private fun state(vararg installedModels: AiModelDescriptor) = ModelCatalogState(
        entries = BundledCatalog.models.map { descriptor ->
            CatalogEntry(descriptor, ModelFit.Fits, installedModels.firstOrNull { it.id == descriptor.id }?.let(::installed), isActive = false)
        },
        capability = capability,
        usingBundledCatalog = true,
    )

    private val catalogState = MutableStateFlow(state())
    private val searchStatus = MutableStateFlow<InstallStatus>(InstallStatus.NotStarted)

    private val catalog = mockk<ModelCatalogRepository>(relaxed = true) {
        every { state } returns catalogState
        every { downloadStatus(any()) } returns flowOf(ModelDownloadStatus.NotStarted)
        every { startDownload(any(), any()) } returns true
    }
    private val embedding = mockk<EmbeddingModelManager>(relaxed = true) {
        every { isInstalled() } returns false
        every { downloadBytes } returns 270_000_000L
        every { status } returns searchStatus
    }
    private val device = DeviceCapabilities { DeviceProfile(11.3, 50L shl 30, bigCoreCount = 4, is64Bit = true) }

    private val gateway = CatalogModelSetupGateway(catalog, embedding, device, RecommendChatModelUseCase())

    @Test
    fun `the offer on a high memory phone has Gemma first, preselected, with a download of Gemma and the search model`() = runTest {
        val offer = gateway.offer().recommendation

        assertThat(offer.preselectedId).isEqualTo(gemma.id)
        assertThat(offer.options.first().id).isEqualTo(gemma.id)
        assertThat(offer.options.first().downloadBytes).isEqualTo(gemma.sizeBytes + 270_000_000L)
        // The reading builds are not chat choices.
        assertThat(offer.options.map { it.id }).containsNoneOf("gemma-4-e2b-it-qat-q4_0", "gemma-4-e4b-it-qat-q4_0")
    }

    @Test
    fun `choosing Gemma downloads Gemma and the search model, not the Qwen reader`() = runTest {
        assertThat(gateway.start(gemma.id, allowMetered = false)).isTrue()

        verify(exactly = 1) { catalog.startDownload(gemma, false) }
        verify(exactly = 0) { catalog.startDownload(reader, any()) }
        verify(exactly = 1) { embedding.install(false) }
    }

    @Test
    fun `choosing a Qwen model still downloads the reader too`() = runTest {
        assertThat(gateway.start(qwen2b.id, allowMetered = true)).isTrue()

        verify(exactly = 1) { catalog.startDownload(reader, true) }
        verify(exactly = 1) { catalog.startDownload(qwen2b, true) }
    }

    @Test
    fun `setup with Gemma alone completes once Gemma and the search model are installed`() = runTest {
        gateway.progress(gemma.id).test {
            val before = expectMostRecentItem()
            assertThat(before.isComplete).isFalse()
            assertThat(before.chat).isEqualTo(SetupPartStatus.NotStarted)

            catalogState.value = state(gemma)
            searchStatus.value = InstallStatus.Installed

            val done = expectMostRecentItem()
            assertThat(done.isComplete).isTrue()
            assertThat(done.chat).isEqualTo(SetupPartStatus.Done)
            // Gemma is its own reader: its row stands for both.
            assertThat(done.reader).isEqualTo(SetupPartStatus.Done)
            cancelAndIgnoreRemainingEvents()
        }
        // The reader was never asked for.
        verify(exactly = 0) { catalog.downloadStatus(reader.id) }
    }
}
