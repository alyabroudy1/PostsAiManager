package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.list.PartyNames
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Profile
import javax.inject.Inject

/**
 * When a managed profile is added or renamed, the documents already read must be asked about it: queues a background check for
 * each document whose text mentions a token of the profile's name (the same pre-filter the decision uses), and for no other.
 * The check itself runs later, quietly, in the background reading queue ([DocumentProcessor.enqueuePeopleCheck]).
 */
class QueueConcernedPeopleCheckUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val processor: DocumentProcessor,
) {

    /** @return how many documents were queued. A profile that is not managed (an organisation) queues none. */
    suspend operator fun invoke(profile: Profile): Int {
        if (!profile.isManaged) return 0
        val matching = documents.getOcrTexts().filter { (_, text) -> PartyNames.mentions(text, profile.name) }.keys
        matching.forEach { processor.enqueuePeopleCheck(it) }
        return matching.size
    }
}
