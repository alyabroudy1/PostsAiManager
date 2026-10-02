package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import javax.inject.Inject

/**
 * "Read again as <family>": re-reads a document with its family forced to the one the person picked from the chip's menu.
 * Reviewed rows survive the re-read (the merge protects them), so only what nobody has looked at changes.
 *
 * Only a family of the current schema can be forced; anything else is rejected rather than handed to the pipeline.
 */
class ReadAgainAsFamilyUseCase @Inject constructor(private val processor: DocumentProcessor) {

    suspend operator fun invoke(documentId: String, familyId: String): PamResult<Unit> {
        if (ExtractionSchema.DEFAULT.family(familyId) == null) {
            return PamResult.Error(PamError.ValidationError("familyId", "unknown family: $familyId"))
        }
        processor.enqueue(documentId, force = true, forcedFamily = familyId)
        return PamResult.Success(Unit)
    }
}
