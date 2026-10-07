package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * "Change type": the person says what the document is. The type is stored as theirs at once (the sections re-present and the list tag
 * changes), and the document is read again with that type pinned, not re-decided, so the fields (the sender, the dates, the amounts)
 * are the ones that fit the type. What the person already confirmed or edited survives the re-read (see [ReprocessOverwritePolicy]).
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
