package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
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
            mayOverwriteFamily(document) -> {
                val keptFamily = stickySensitiveFamily(document)?.takeIf { SCHEMA.family(family)?.sensitive != true }
                withLayout.copy(
                    extractionType = keptFamily ?: family,
                    extractionTypeConfidence = if (keptFamily != null) document.extractionTypeConfidence else read.documentTypeConfidence,
                    // An empty list means "not scored yet" (a first stage that leaves topics to the second), not "no topics".
                    topics = withStickySensitive(document, read.topics.ifEmpty { document.topics }),
                )
            }
            else -> withLayout
        }
    }

    /**
     * The topics a second stage scored (a profile that leaves them out of the first): stored where the family is the model's to set.
     * A sensitive topic already stored stays, whatever the model scored.
     */
    fun applyLateTopics(document: Document, read: DocumentUnderstanding): Document {
        if (!mayOverwriteFamily(document) || read.topics.isEmpty()) return document
        val topics = withStickySensitive(document, read.topics)
        return if (topics != document.topics) document.copy(topics = topics) else document
    }

    /**
     * Sensitivity is sticky across every model re-read: only a person's action (Change type, an edit) may drop it. The sensitive topics
     * the document carries, including those its legacy type stands for ([LegacyTypes]), are kept in [topics].
     */
    private fun withStickySensitive(document: Document, topics: List<String>): List<String> {
        val legacyTopics = LegacyTypes.of(document.extractionType)?.topics.orEmpty()
        val sticky = (document.topics + legacyTopics).filter { SCHEMA.topic(it)?.sensitive == true }.distinct()
        return topics + sticky.filter { id -> topics.none { it.equals(id, ignoreCase = true) } }
    }

    /** The stored family id when it, or the family its legacy type stands for, is sensitive; else null. */
    private fun stickySensitiveFamily(document: Document): String? {
        val type = document.extractionType
        SCHEMA.family(type)?.takeIf { it.sensitive }?.let { return it.id }
        val legacyFamily = LegacyTypes.of(type)?.family
        return SCHEMA.family(legacyFamily)?.takeIf { it.sensitive }?.id
    }

    private val SCHEMA get() = ExtractionSchema.DEFAULT

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

    /**
     * The actions a second stage chose, replacing the stored ones (nobody edits them: a person's own say is their confirmed or edited
     * fields, which the actions are rendered from when they are shown). A reading that chose none (a first stage, a failed scoring) leaves them.
     */
    fun applyActions(document: Document, read: DocumentUnderstanding): Document {
        val items = read.actionItems ?: return document
        return document.copy(actionItems = items)
    }
}
