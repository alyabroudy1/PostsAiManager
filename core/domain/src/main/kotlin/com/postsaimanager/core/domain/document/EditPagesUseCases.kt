package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.usecase.IndexDocumentUseCase
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.PageChange
import javax.inject.Inject

private fun invalid(message: String): PamResult<Unit> = PamResult.Error(PamError.ValidationError("pages", message))

/**
 * The chat's passages of a letter are made again from its pages as they are now, so a citation says the page the words are on. Indexing
 * never fails a letter (it degrades to keyword search), so a failure here does not undo the change.
 */
private suspend fun reindex(documents: DocumentRepository, index: IndexDocumentUseCase, documentId: String) {
    val pages = (documents.getDocumentPages(documentId) as? PamResult.Success)?.data ?: return
    index(documentId, pages.sortedBy { it.pageNumber }.map { IndexDocumentUseCase.PageText(it.pageNumber, it.ocrText.orEmpty()) })
}

private suspend fun pagesOf(documents: DocumentRepository, documentId: String): List<DocumentPage>? =
    (documents.getDocumentPages(documentId) as? PamResult.Success)?.data?.sortedBy { it.pageNumber }

/**
 * The user deletes a page of a letter. The letter keeps at least one page. The page number of every value read from a later page, of every
 * chat citation and of every form field moves with its page (a value read from the deleted page keeps its text and loses the place), the
 * passages are made again, and the image file goes. Reading the letter again is for the person to ask, and keeps every value they set.
 */
class DeleteDocumentPageUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val index: IndexDocumentUseCase,
) {

    suspend operator fun invoke(documentId: String, pageNumber: Int): PamResult<Unit> {
        val pages = pagesOf(documents, documentId) ?: return invalid("no such letter")
        if (pages.none { it.pageNumber == pageNumber }) return invalid("no page $pageNumber")
        if (pages.size <= 1) return invalid("a letter keeps at least one page")
        val result = documents.changePages(documentId, PageChange.Delete(pageNumber))
        if (result is PamResult.Success) reindex(documents, index, documentId)
        return result
    }
}

/**
 * The user puts the pages of a letter in a new order: [order] lists the current page numbers in the order they should now have, every page
 * exactly once. What refers to a page by its number follows its page, and the passages are made again.
 */
class ReorderDocumentPagesUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val index: IndexDocumentUseCase,
) {

    suspend operator fun invoke(documentId: String, order: List<Int>): PamResult<Unit> {
        val pages = pagesOf(documents, documentId) ?: return invalid("no such letter")
        val numbers = pages.map { it.pageNumber }
        if (order.sorted() != numbers.sorted()) return invalid("the order must list every page once")
        if (order == numbers) return PamResult.Success(Unit)
        val result = documents.changePages(documentId, PageChange.Reorder(order))
        if (result is PamResult.Success) reindex(documents, index, documentId)
        return result
    }

    /** The order that moves page [pageNumber] by [delta] places (-1: one earlier), or null when it cannot move that way. */
    suspend fun moved(documentId: String, pageNumber: Int, delta: Int): List<Int>? {
        val numbers = pagesOf(documents, documentId)?.map { it.pageNumber } ?: return null
        val from = numbers.indexOf(pageNumber).takeIf { it >= 0 } ?: return null
        val to = from + delta
        if (to !in numbers.indices) return null
        return numbers.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
}
