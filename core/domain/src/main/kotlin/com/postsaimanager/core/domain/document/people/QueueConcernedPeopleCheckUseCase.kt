package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Profile
import javax.inject.Inject

/**
 * When a managed profile is added or renamed, the documents already read must be asked about it: for each document whose text mentions
 * a token of the profile's name (the same pre-filter the decision uses), and for no other, the stored decision is set back to "not
 * asked yet" and a background check is queued ([DocumentProcessor.enqueuePeopleCheck]). The check itself runs later, quietly.
 */
class QueueConcernedPeopleCheckUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val processor: DocumentProcessor,
) {

    /** @return how many documents were queued. A profile that is not managed (an organisation) queues none. */
    suspend operator fun invoke(profile: Profile): Int {
        if (!profile.isManaged) return 0
        val matching = documents.getOcrTexts().filter { (_, text) -> PartyNames.mentions(text, profile.name) }.keys
        if (matching.isEmpty()) return 0
        documents.resetConcernedProfiles(matching)
        matching.forEach { processor.enqueuePeopleCheck(it) }
        return matching.size
    }
}

/**
 * The one-off backfill: queues the people check (quiet work, one per document, `KEEP`) for every document a model has read whose
 * decision is still "not asked yet". The check writes its answer, so each document is asked once; one with no profile passing the
 * name-token pre-filter is settled to "nobody" without a model.
 */
class BackfillConcernedPeopleUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val processor: DocumentProcessor,
) {

    /** @return how many documents were queued. */
    suspend operator fun invoke(): Int {
        val waiting = documents.getDocumentIdsAwaitingPeopleCheck()
        waiting.forEach { processor.enqueuePeopleCheck(it) }
        return waiting.size
    }
}
