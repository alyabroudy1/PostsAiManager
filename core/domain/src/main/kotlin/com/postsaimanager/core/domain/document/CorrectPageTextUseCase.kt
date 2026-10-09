package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import javax.inject.Inject

/**
 * The user corrects the recognised text of a page. The text is stored as theirs: a re-read reads it in place of the page's word blocks (the
 * blocks it is given are plain lines, see `PlainTextBlocks`) and never replaces it with a fresh recognition. The chat's passages of the
 * letter are made again from the corrected pages, so questions and search find the corrected words. Reading the letter again is offered
 * to the person, not started here: it never overwrites a value they set.
 */
class CorrectPageTextUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val index: IndexDocumentUseCase,
) {

    /** @param text the page's whole text as the person left it; blank is allowed (a page with nothing on it) */
    suspend operator fun invoke(documentId: String, pageNumber: Int, text: String): PamResult<Unit> {
        val clean = text.replace("\r\n", "\n").trim()
        val pages = when (val loaded = documents.getDocumentPages(documentId)) {
            is PamResult.Success -> loaded.data
            is PamResult.Error -> return PamResult.Error(loaded.error)
        }
        if (pages.none { it.pageNumber == pageNumber }) return PamResult.Error(PamError.ValidationError("page", "no page $pageNumber"))
        when (val stored = documents.setPageTextByUser(documentId, pageNumber, clean)) {
            is PamResult.Error -> return stored
            is PamResult.Success -> Unit
        }
        val texts = pages.sortedBy { it.pageNumber }.map { page ->
            IndexDocumentUseCase.PageText(page.pageNumber, if (page.pageNumber == pageNumber) clean else page.ocrText.orEmpty())
        }
        // Indexing never fails a letter (it degrades to keyword search); a failure here does not undo the correction.
        index(documentId, texts)
        return PamResult.Success(Unit)
    }
}
