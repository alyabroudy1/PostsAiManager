package com.postsaimanager.core.domain.document

import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TitleSource

/**
 * What a reading may write on the document itself (the fields' own protection is `MergeExtractionUseCase`), and the one place
 * that writes it. The document-level half of "a person's choice is never undone":
 *
 * - the family, the topics and the layout template only while the model chose them, never after a person did;
 * - the title only where [DocumentTitlePolicy] allows (never a person's, never real words an older reading wrote);
 * - the summary unless a person wrote it.
 *
 * The apply functions are pure: they take the stored [Document] and what the reading understood and return the document to store.
 * `DocumentProcessingPipeline` (the first stage and the second) calls them; it decides nothing about these columns itself.
 */
object ReprocessOverwritePolicy {

    /** True when a re-read may replace the family and the topics. */
    fun mayOverwriteFamily(familySource: FamilySource): Boolean = familySource == FamilySource.MODEL

    /** True when a re-read may replace the summary. A missing source (no summary yet) may be filled. */
    fun mayOverwriteSummary(summarySource: SummarySource?): Boolean = summarySource != SummarySource.USER

    fun mayOverwriteFamily(document: Document): Boolean = mayOverwriteFamily(document.familySource)

    fun mayOverwriteSummary(document: Document): Boolean = mayOverwriteSummary(document.summarySource)

    /**
     * The family, topics, confidence and layout template of a reading in which a model read the letter ([read] with a family).
     *
     * @param forcedFamily the family a person just chose ("Read again as ..."): stored as the person's choice, whatever was there.
     *   Null: the model's family replaces the stored one only while [FamilySource.MODEL] chose it. The layout template is the
     *   letter's own shape, not a choice, so it is always the latest reading's.
     */
    fun applyFamily(document: Document, read: DocumentUnderstanding, forcedFamily: String? = null): Document {
        val family = read.documentType.ifBlank { null } ?: return document
        val withLayout = read.layoutTemplate?.let { document.copy(layoutTemplate = it) } ?: document
        return when {
            forcedFamily != null -> withLayout.copy(
                extractionType = family, extractionTypeConfidence = read.documentTypeConfidence, topics = read.topics, familySource = FamilySource.USER,
            )
            mayOverwriteFamily(document) -> withLayout.copy(
                extractionType = family, extractionTypeConfidence = read.documentTypeConfidence, topics = read.topics,
            )
            else -> withLayout
        }
    }

    /** The topics a second stage scored (a profile that leaves them out of the first): stored where the family is the model's to set. */
    fun applyLateTopics(document: Document, read: DocumentUnderstanding): Document =
        if (mayOverwriteFamily(document) && read.topics.isNotEmpty() && read.topics != document.topics) document.copy(topics = read.topics) else document

    /**
     * The composed title ([DocumentUnderstanding.titleCode] with its args), where [DocumentTitlePolicy] allows: over an app default or an
     * earlier composed title, never over a person's title nor over real words. [DocumentUnderstanding.title] is the plain-text fallback.
     */
    fun applyTitle(document: Document, read: DocumentUnderstanding): Document {
        val code = read.titleCode ?: return document
        if (read.title.isBlank()) return document
        if (!DocumentTitlePolicy.modelTitleMayReplace(isUserTitle = document.isUserTitle, titleCode = document.titleCode)) return document
        return document.copy(title = read.title, titleCode = code, titleArgs = read.titleArgs, titleSource = TitleSource.COMPOSED)
    }

    /**
     * The summary the writer settled on ([DocumentUnderstanding.summarySource] says whether it is the model's sentences or the template),
     * unless a person wrote the stored one. A reading with no summary (a first stage, a second that could not write) leaves the stored one.
     */
    fun applySummary(document: Document, read: DocumentUnderstanding): Document {
        val source = read.summarySource ?: return document
        if (!mayOverwriteSummary(document)) return document
        return document.copy(
            summary = read.summary.ifBlank { null }, summarySource = source, summaryCode = read.summaryCode, summaryArgs = read.summaryArgs,
        )
    }
}
