package com.postsaimanager.setup

import com.postsaimanager.core.ai.catalog.BundledCatalog
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.ModelCatalogRepository
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.EmbeddingModelManager
import com.postsaimanager.core.domain.setup.DeviceCapabilities
import com.postsaimanager.core.domain.setup.ModelSetupGateway
import com.postsaimanager.core.domain.setup.RecommendChatModelUseCase
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupPartStatus
import com.postsaimanager.core.model.SetupProgress
import com.postsaimanager.core.model.isItsOwnReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run setup over the existing download machinery: the reader model and the chosen chat model through [ModelCatalogRepository]
 * (`ModelDownloadWorker` underneath) and the search model through [EmbeddingModelManager]. Nothing here downloads by itself.
 *
 * The reader (the catalog's [ModelRole.READER_AND_CHAT] model) is installed, once, even when it is also the chosen chat model, unless
 * the chosen chat model reads letters itself ([AiModelDescriptor.readsDocuments], Gemma): then only that model and the search model
 * are downloaded. What it owns that the Models screen does at the UI layer: registering a finished download as installed
 * ([ModelCatalogRepository.onDownloadComplete]), so setup works without that screen being open, and making the chosen model the
 * active chat model (reading stays on the reader: see `InstalledIndex.readerModel`).
 */
@Singleton
class CatalogModelSetupGateway @Inject constructor(
    private val catalog: ModelCatalogRepository,
    private val embedding: EmbeddingModelManager,
    private val device: DeviceCapabilities,
    private val recommend: RecommendChatModelUseCase,
) : ModelSetupGateway {

    private suspend fun entries(): List<CatalogEntry> = catalog.state.first().entries

    private suspend fun readerOf(entries: List<CatalogEntry>): AiModelDescriptor =
        entries.firstOrNull { it.descriptor.role == ModelRole.READER_AND_CHAT }?.descriptor ?: BundledCatalog.readerModel

    override suspend fun offer(): SetupOffer {
        val entries = entries()
        val reader = readerOf(entries)
        val recommendation = recommend(
            device = device.current(),
            catalog = entries.map { it.descriptor }.ifEmpty { listOf(reader) },
            installedIds = entries.filter { it.isInstalled }.map { it.descriptor.id }.toSet(),
            searchModelBytes = if (embedding.isInstalled()) 0L else embedding.downloadBytes,
        )
        val readerFit = entries.firstOrNull { it.descriptor.id == reader.id }?.fit
        return SetupOffer(recommendation, canInstallChatModel = readerFit?.canDownload ?: reader.isInstallable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun progress(chatModelId: String): Flow<SetupProgress> = flow {
        val entries = entries()
        emit(readerOf(entries) to entries.firstOrNull { it.descriptor.id == chatModelId }?.descriptor)
    }.flatMapLatest { (reader, chat) ->
        // One download and one row when the chosen chat model is its own reader (the reader itself, or a model that reads letters).
        val separateChat = chat?.takeUnless { it.isItsOwnReader(reader) }
        val single = if (separateChat == null) chat ?: reader else null
        combine(
            part(single ?: reader, activate = separateChat == null),
            separateChat?.let { part(it, activate = true) } ?: flowOf(null),
            embedding.status,
        ) { readerPart, chatPart, search ->
            SetupProgress(chat = chatPart ?: readerPart, search = searchPartStatus(search), reader = readerPart)
        }
    }.distinctUntilChanged()

    override suspend fun start(chatModelId: String, allowMetered: Boolean): Boolean {
        val entries = entries()
        val reader = readerOf(entries)
        val chat = entries.firstOrNull { it.descriptor.id == chatModelId }?.descriptor ?: reader
        // A chat model that reads letters itself (Gemma) is the only model downloaded: the Qwen reader is an optional extra later.
        val started = (chat.isItsOwnReader(reader) || ensureInstalledOrStarted(reader, allowMetered)) &&
            ensureInstalledOrStarted(chat, allowMetered)
        if (isInstalled(chat.id)) catalog.setActive(chat.id)
        if (!embedding.isInstalled()) embedding.install(allowMetered)
        return started
    }

    override fun cancel() {
        BundledCatalog.models.forEach { catalog.cancelDownload(it.id) }
        embedding.cancel()
    }

    /** True when [model] is installed or its download was enqueued; false when it has no verified source. */
    private suspend fun ensureInstalledOrStarted(model: AiModelDescriptor, allowMetered: Boolean): Boolean =
        isInstalled(model.id) || catalog.startDownload(model, allowMetered)

    private suspend fun isInstalled(id: String): Boolean = installed(id).first()

    private fun installed(id: String): Flow<Boolean> = catalog.state
        .map { state -> state.entries.any { it.descriptor.id == id && it.isInstalled } }
        .distinctUntilChanged()

    private fun part(model: AiModelDescriptor, activate: Boolean): Flow<SetupPartStatus> = combine(
        installed(model.id),
        catalog.downloadStatus(model.id).onEach { registerIfFinished(model, it, activate) },
    ) { installed, download -> chatPartStatus(installed, download, model.sizeBytes) }

    /** Idempotent: the store replaces an entry with the same id and keeps the active model, unless [activate] makes this one active. */
    private suspend fun registerIfFinished(model: AiModelDescriptor, status: ModelDownloadStatus, activate: Boolean) {
        if (status is ModelDownloadStatus.Complete && status.filePath.isNotBlank() && !isInstalled(model.id)) {
            catalog.onDownloadComplete(model, status.filePath)
            if (activate) catalog.setActive(model.id)
        }
    }
}
