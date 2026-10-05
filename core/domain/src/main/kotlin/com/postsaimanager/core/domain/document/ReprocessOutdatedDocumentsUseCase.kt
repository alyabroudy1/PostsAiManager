package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.TimelineRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.TimelineCodes
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * When the extractor improves ([ExtractorVersion.CURRENT] is bumped), schedules the finished letters
 * an older version read to be re-read in the background. Called once on app start, in the main
 * process only — see `PostsAiManagerApp`.
 *
 * - **Which**: not trashed, [DocumentStatus.EXTRACTED], and [ExtractorVersion.isOutdated]. A letter
 *   still new, queued, processing or failed is the ordinary pipeline's business, not this one's.
 * - **How many**: at most [limit] per start, newest first, to spread the cost; the next start
 *   continues. Idempotent: scheduling is `KEEP` unique work, and a letter drops out of the list once
 *   it carries the current version.
 * - **How**: through [DocumentProcessor.enqueueReprocess], which is low priority, constrained and
 *   invisible; the merge keeps everything the user confirmed or edited.
 * - **Failure**: a re-read that failed records `reprocess_failed` on the letter's timeline. A letter
 *   with [MAX_ATTEMPTS] of them is left alone: one try plus one retry on a later start.
 * - **Off switches**: the "Update older letters automatically" setting, and having no model (a re-read
 *   without one cannot improve anything).
 *
 * @return how many letters were scheduled.
 */
class ReprocessOutdatedDocumentsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val timelineRepository: TimelineRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val activeModelProvider: ActiveModelProvider,
    private val documentProcessor: DocumentProcessor,
) {

    suspend operator fun invoke(limit: Int = DEFAULT_LIMIT): Int {
        if (!userPreferencesRepository.getUserPreferences().first().updateOlderLettersAutomatically) return 0
        if (activeModelProvider.extractionModelPath() == null) return 0

        val candidates = documentRepository.getDocuments().first()
            .filter(::isCandidate)
            .sortedByDescending { it.createdAt }

        var scheduled = 0
        for (document in candidates) {
            if (scheduled >= limit) break
            if (failedAttempts(document.id) >= MAX_ATTEMPTS) continue
            documentProcessor.enqueueReprocess(document.id)
            scheduled++
        }
        return scheduled
    }

    private fun isCandidate(document: Document): Boolean =
        !document.isTrashed &&
            document.status == DocumentStatus.EXTRACTED &&
            ExtractorVersion.isOutdated(document.extractorVersion)

    private suspend fun failedAttempts(documentId: String): Int =
        timelineRepository.getTimelineForDocument(documentId).first()
            .count { it.code == TimelineCodes.REPROCESS_FAILED }

    companion object {
        /** Letters scheduled per app start. */
        const val DEFAULT_LIMIT = 5

        /** The first try and one retry on a later start. */
        const val MAX_ATTEMPTS = 2
    }
}
