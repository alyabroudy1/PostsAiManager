package com.postsaimanager.core.domain.document.people

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.timeline.SyncEventLinksUseCase
import com.postsaimanager.core.model.ConcernedSource
import com.postsaimanager.core.model.Document
import javax.inject.Inject

/**
 * What the people check may do to the list of household people a document is for or about, the one place that decides it: it asks and
 * writes only while the model decided the list. A list a person set ([ConcernedSource.USER]) is never asked again, never reset and never
 * replaced.
 */
object ConcernedPeoplePolicy {

    fun mayDecide(document: Document): Boolean = document.concernedSource != ConcernedSource.USER
}

/**
 * The user says who a letter is for or about (the household chips on the letter): the list is stored as theirs, so the people check, its
 * reset when a profile is added or renamed, and every re-read leave it alone. The letter's timeline events follow the new list.
 */
class SetConcernedPeopleUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val syncEventLinks: SyncEventLinksUseCase,
) {

    /** @param profileIds the household people the letter is for or about; empty: none of them. */
    suspend operator fun invoke(documentId: String, profileIds: List<String>): PamResult<Unit> {
        documents.setConcernedProfilesByUser(documentId, profileIds.distinct())
        syncEventLinks(documentId)
        return PamResult.Success(Unit)
    }
}
