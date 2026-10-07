package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * "Change type": the person says what kind of document it is (one of the broad categories). The category is stored as theirs at once (the
 * sections re-present and the list tag changes) and stays theirs: no later reading decides it again.
 *
 * It is context, not a switch. The type never decided which questions are asked, so there is nothing to re-gate; the document is read again
 * with "The user says this document is a <category>." added to every question and to the name written for the document (see
 * `ExtractionV2Pipeline`'s `forcedFamily`), which can only help the model read what the person already knows. What the person already
 * confirmed or edited survives the re-read, field by field (see [ReprocessOverwritePolicy] and `MergeExtractionUseCase`).
 */
class ChangeDocumentFamilyUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val readAgain: ReadAgainAsFamilyUseCase,
) {

    suspend operator fun invoke(documentId: String, familyId: String): PamResult<Unit> {
        if (ExtractionSchema.DEFAULT.family(familyId) == null) {
            return PamResult.Error(PamError.ValidationError("familyId", "unknown family: $familyId"))
        }
        val stored = documents.setDocumentFamily(documentId, familyId)
        if (stored is PamResult.Error) return stored
        return readAgain(documentId, familyId)
    }
}
