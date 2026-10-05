package com.postsaimanager.setup

import com.postsaimanager.core.ai.catalog.BundledCatalog
import com.postsaimanager.core.ai.catalog.ModelCatalogRepository
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.EmbeddingModelManager
import com.postsaimanager.core.domain.setup.ModelSetupGateway
import com.postsaimanager.core.model.SetupOffer
import com.postsaimanager.core.model.SetupProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run setup over the existing download machinery: the catalog's recommended model through [ModelCatalogRepository]
 * (`ModelDownloadWorker` underneath) and the search model through [EmbeddingModelManager]. Nothing here downloads by itself.
 *
 * One thing it owns that the Models screen does at the UI layer: registering a finished chat download as installed
 * ([ModelCatalogRepository.onDownloadComplete]), so setup works without that screen being open.
 */
@Singleton
class CatalogModelSetupGateway @Inject constructor(
    private val catalog: ModelCatalogRepository,
    private val embedding: EmbeddingModelManager,
) : ModelSetupGateway {

    private val chatModel get() = BundledCatalog.firstRunModel

    override suspend fun offer(): SetupOffer {
        val model = chatModel
        val fit = catalog.state.first().entries.firstOrNull { it.descriptor.id == model.id }?.fit
        return SetupOffer(
            chatModelName = model.name,
            chatModelBytes = model.sizeBytes,
            searchModelBytes = embedding.downloadBytes,
            canInstallChatModel = fit?.canDownload ?: model.isInstallable,
        )
    }

    override val progress: Flow<SetupProgress> = combine(
        chatInstalled(),
        catalog.downloadStatus(chatModel.id).onEach(::registerIfFinished),
        embedding.status,
    ) { installed, download, search ->
        SetupProgress(
            chat = chatPartStatus(installed, download, chatModel.sizeBytes),
            search = searchPartStatus(search),
        )
    }.distinctUntilChanged()

    override suspend fun start(allowMetered: Boolean): Boolean {
        val started = chatInstalledNow() || catalog.startDownload(chatModel, allowMetered)
        if (!embedding.isInstalled()) embedding.install(allowMetered)
        return started
    }

    override fun cancel() {
        catalog.cancelDownload(chatModel.id)
        embedding.cancel()
    }

    private fun chatInstalled(): Flow<Boolean> = catalog.state
        .map { state -> state.entries.any { it.descriptor.id == chatModel.id && it.isInstalled } }
        .distinctUntilChanged()

    private suspend fun chatInstalledNow(): Boolean = chatInstalled().first()

    /** Idempotent: the store replaces an entry with the same id and keeps the active model. */
    private suspend fun registerIfFinished(status: ModelDownloadStatus) {
        if (status is ModelDownloadStatus.Complete && status.filePath.isNotBlank() && !chatInstalledNow()) {
            catalog.onDownloadComplete(chatModel, status.filePath)
        }
    }
}
