package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Core domain model representing a scanned/imported document.
 */
@Serializable
data class Document(
    val id: String,
    val title: String,
    val status: DocumentStatus = DocumentStatus.NEW,
    val documentType: DocumentType? = null,
    val language: String? = null,
    val sourceType: SourceType,
    val thumbnailPath: String? = null,
    val pageCount: Int = 0,
    val isFavorite: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
    /**
     * Set when the last extraction had to cut this document's layout to fit its context
     * budget (5.4, `InputTruncation`) — how many of [pageCount] pages the model actually
     * saw. Null means the whole document was read, which is both the common case and what a
     * document scanned before this existed reads as.
     */
    val extractionPagesRead: Int? = null,
    val extractionTotalPages: Int? = null,
    /**
     * Set when the document is in the trash (moved there by the user, or by processing
     * itself refusing to write after a race with a delete). Null means "not deleted". A
     * trashed document is hidden from every list, search and chat-retrieval path, but its
     * rows and files are kept until it is restored or purged — see
     * documentation/07-document-pipeline.md, "Deleting documents".
     */
    val deletedAt: Long? = null,
    /** The document type id the model chose (`bill`, `authority_tax`, ...), null before a model read it. */
    val extractionType: String? = null,
    /** The model's own confidence in [extractionType]. */
    val extractionTypeConfidence: Float? = null,
    /** The topic ids the model found, best first (`health`, `tax`, ...); empty before a model read it. */
    val topics: List<String> = emptyList(),
    /** Which extractor version wrote this document's machine values; drives reprocessing on a version bump. */
    val extractorVersion: String? = null,
    /** A person chose [title]; extraction must not replace it. */
    val isUserTitle: Boolean = false,
    /** Up to three questions the model suggested for this document's chat, in the letter's language. */
    val suggestedQuestions: List<String> = emptyList(),
    /** The model's one- or two-sentence summary, shown marked as an AI summary. */
    val summary: String? = null,
    /**
     * When [title] is a default the app wrote rather than words the model or a person chose, the code
     * of that title (`scanned_pages`) with its [titleArgs] (the page count). The UI renders the
     * localised sentence from the code; [title] keeps the English text as a fallback for places that
     * cannot resolve resources (search, file names). Null once the title is real words.
     */
    val titleCode: String? = null,
    val titleArgs: List<String> = emptyList(),
    /** Who chose [extractionType] (the family) and [topics]; a re-read overwrites them only for [FamilySource.MODEL]. */
    val familySource: FamilySource = FamilySource.MODEL,
    /** Where [title] came from. */
    val titleSource: TitleSource = TitleSource.DEFAULT,
    /** Where [summary] came from; null when there is no summary. A re-read keeps it when it is [SummarySource.USER]. */
    val summarySource: SummarySource? = null,
    /** When the summary is a template, its code; [summaryArgs] are the values it is rendered from. */
    val summaryCode: String? = null,
    val summaryArgs: List<String> = emptyList(),
    /**
     * What the reader has to do, as the action kinds the second stage chose (at most two) with the stored fields each rests on; the
     * line is rendered from them when it is shown. Empty when the letter asks nothing or nothing was chosen (no model, a failed stage);
     * the Extracted tab then has no "What you need to do" section.
     */
    val actionItems: List<ActionItem> = emptyList(),
    /** The id of the layout template the letter matched, for display and debugging; null when none. */
    val layoutTemplate: String? = null,
    /** How many times the reading's second stage ran without settling a summary; at the limit the template summary is stored. */
    val enrichmentAttempts: Int = 0,
    /**
     * A second stage is owed: set when a reading's first stage is stored, cleared when the second settles (a summary written, or the
     * limit of retries reached). Startup recovery looks for it, since a re-read keeps its earlier summary and so cannot be found by
     * a missing summary.
     */
    val enrichmentPending: Boolean = false,
    /**
     * The managed profiles (Me and family members) the model decided this document is for or about, over the whole letter; the
     * list's person chips. null: not asked yet (a backfill or the next check asks); empty: asked, nobody. Never set by name matching.
     */
    val concernedProfileIds: List<String>? = null,
    /**
     * SHA-256 (lower-case hex) of the file this document was imported from, so adding the same file again can be noticed. Null for a
     * scan and for a document imported before this existed. For several images imported together it is the hash of their hashes.
     */
    val sourceHash: String? = null,
    /**
     * The original PDF kept privately (a `file://` URI inside the app's own storage), so "Open original" and "Share original" exist and
     * a digital PDF's quality is not lost to the page images. Null when there is none. Deleted together with the document.
     * At creation the use case passes where the file is now (a temporary copy); the repository moves it and stores the final location.
     */
    val originalFilePath: String? = null,
) {
    val isTrashed: Boolean get() = deletedAt != null

    /**
     * The title to show. A default the app wrote is rendered by [scannedPages] (the UI's localised
     * plural) from its code and page count; anything else, or a code this build does not know, is
     * [title] as stored.
     */
    fun displayTitle(scannedPages: (count: Int) -> String): String {
        if (titleCode == DocumentTitleCodes.SCANNED_PAGES) {
            titleArgs.firstOrNull()?.toIntOrNull()?.let { return scannedPages(it) }
        }
        return title
    }
}

/**
 * Stored as its `name` in Room ([DocumentEntity][com.postsaimanager.core.data.database.entity]
 * keeps `status` as a plain `TEXT` column read back through `valueOf`), so adding a value here
 * is additive and needs no migration — only code that exhaustively `when`s over every value
 * does.
 */
@Serializable
enum class DocumentStatus {
    NEW,

    /** Enqueued for processing but not yet running — see `DocumentProcessor.enqueue`. */
    QUEUED,
    PROCESSING,
    EXTRACTED,
    REVIEWED,
    ARCHIVED,

    /** The last processing attempt failed; the document keeps whatever it had before. */
    FAILED,
}

@Serializable
enum class DocumentType {
    OFFICIAL_LETTER,
    INVOICE,
    NOTICE,
    FORM,
    CONTRACT,
    CERTIFICATE,
    RECEIPT,
    OTHER,
}

@Serializable
enum class SourceType {
    CAMERA,
    UPLOAD,
    PDF_IMPORT,
}
