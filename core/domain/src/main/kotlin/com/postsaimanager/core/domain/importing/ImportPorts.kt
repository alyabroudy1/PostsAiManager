package com.postsaimanager.core.domain.importing

import kotlinx.coroutines.flow.Flow

/**
 * Turns PDFs and images into the page images the document pipeline reads. UI-free and free of any document storage, so the chat can
 * reuse it later to show a PDF to the model. `:core:data` implements it with the platform's `PdfRenderer` and `ImageDecoder`.
 *
 * Everything a batch owns (the private copies, the rendered pages, the thumbnails) lives under the batch's id and is removed by
 * [discard]; a caller that stops, for any reason, calls it.
 */
interface PageImageSource {

    /** True when the platform can open a password-protected PDF (it can ask for the password); false below Android 15. */
    val supportsPasswordPdfs: Boolean

    /**
     * Copies [uri] (a `content://` or `file://` URI, read once and at once) into the batch, checks the real type from its first bytes,
     * the size ([ImportLimits.MAX_FILE_BYTES]) and a PDF's page count ([ImportLimits.MAX_PDF_PAGES]), and hashes it. Nothing is left
     * behind for a rejected file.
     */
    suspend fun stage(batchId: String, uri: String): StageResult

    /** Opens a locked PDF with [password]; on success the file now has its page count (and the page cap has been checked). */
    suspend fun unlock(file: StagedFile, password: String): StageResult

    /** A small JPEG of the first page, for the confirm sheet; null when it cannot be made (the sheet shows a placeholder). */
    suspend fun thumbnail(batchId: String, file: StagedFile, password: String?): String?

    /**
     * Renders every page of [file] white-backed, longest side [ImportLimits.PAGE_LONGEST_SIDE_PX], as JPEG into the batch's private
     * pages folder. An image becomes its one page (EXIF rotation applied, EXIF dropped). All or nothing: on a failure no page of
     * [file] is left behind.
     */
    suspend fun renderPages(batchId: String, file: StagedFile, password: String?): RenderResult

    /** Removes the pages [renderPages] made (they were copied into the document); the staged files stay for the rest of the batch. */
    suspend fun deletePages(pagePaths: List<String>)

    /** Removes everything the batch owns. Safe to call twice and on a batch that never existed. */
    suspend fun discard(batchId: String)
}

/**
 * The background side of importing: hands a confirmed [ImportRequest] to an expedited job that survives leaving the app, and tells
 * the list what is going on, so it can show "Importing...".
 */
interface ImportQueue {

    suspend fun submit(request: ImportRequest)

    /** Waits until the job of [batchId] has finished and says what it made. A job that vanished reads as nothing made, all failed. */
    suspend fun awaitResult(batchId: String): ImportResult

    /** Running and failed import jobs, until the failure is dismissed. */
    val status: Flow<ImportStatus>

    /** The user has seen the failure; forget finished jobs. */
    fun dismissFailures()
}
