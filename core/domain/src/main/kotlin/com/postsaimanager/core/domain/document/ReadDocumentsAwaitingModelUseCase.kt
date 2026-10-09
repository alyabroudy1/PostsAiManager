package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.TimelineCodes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * A letter scanned while the AI model was still downloading is stored with what code could find (OCR and values without meaning).
 * This reads those letters properly as soon as a model is there: [invoke] schedules them, [watch] does so whenever a model becomes
 * installed (and once at start when one already is).
 *
 * Scheduling is the quiet reprocess ([DocumentProcessor.enqueueReprocess], urgent: no wait for a charger), which keeps everything a
 * person confirmed or edited. A letter whose re-read failed twice is left alone, as for `ReprocessOutdatedDocumentsUseCase`.
 */
class ReadDocumentsAwaitingModelUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val timelineRepository: TimelineRepository,
    private val installedModels: InstalledModelsRepository,
    private val activeModelProvider: ActiveModelProvider,
    private val documentProcessor: DocumentProcessor,
) {

    /** @return how many letters were scheduled. */
    suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): Int {
        // Not the llama.cpp reader alone: with Gemma as the only model installed the Gemma reader reads on the chat model.
        if (!activeModelProvider.canReadDocuments()) return 0
        var scheduled = 0
        for (document in documentRepository.getDocuments().first().filter(::awaits).sortedByDescending { it.createdAt }) {
            if (scheduled >= limit) break
            if (failedAttempts(document.id) >= MAX_ATTEMPTS) continue
            documentProcessor.enqueueReprocess(document.id, urgent = true)
            scheduled++
        }
        return scheduled
    }

    /** Never returns: schedules the waiting letters each time a model appears. Runs in the application scope. */
    suspend fun watch() {
        installedModels.installed.map { it.isNotEmpty() }.distinctUntilChanged().collect { hasModel ->
            if (hasModel) invoke()
        }
    }

    private fun awaits(document: Document): Boolean =
        !document.isTrashed &&
            document.status == DocumentStatus.EXTRACTED &&
            ExtractorVersion.awaitsModel(document.extractorVersion)

    private suspend fun failedAttempts(documentId: String): Int =
        timelineRepository.getTimelineForDocument(documentId).first().count { it.code == TimelineCodes.REPROCESS_FAILED }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_ATTEMPTS = 2
    }
}
