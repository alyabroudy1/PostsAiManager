package com.postsaimanager.core.domain.document

import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SummarySource

/**
 * What a re-read may overwrite on the document itself (the fields' own protection is
 * `MergeExtractionUseCase`). The document-level half of "a person's choice is never undone":
 *
 * - the family and topics only while the model chose them, never after a person did;
 * - the summary unless a person wrote it;
 * - the title stays with [DocumentTitlePolicy].
 *
 * TODO(P4): `DocumentProcessingPipeline` decides these inline today; route it through this class.
 */
object ReprocessOverwritePolicy {

    /** True when a re-read may replace the family and the topics. */
    fun mayOverwriteFamily(familySource: FamilySource): Boolean = familySource == FamilySource.MODEL

    /** True when a re-read may replace the summary. A missing source (no summary yet) may be filled. */
    fun mayOverwriteSummary(summarySource: SummarySource?): Boolean = summarySource != SummarySource.USER

    fun mayOverwriteFamily(document: Document): Boolean = mayOverwriteFamily(document.familySource)

    fun mayOverwriteSummary(document: Document): Boolean = mayOverwriteSummary(document.summarySource)
}
