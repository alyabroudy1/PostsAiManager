package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentPreview
import com.postsaimanager.core.model.PreviewPage
import com.postsaimanager.core.model.TextBounds
import javax.inject.Inject

/**
 * Everything the in-place page preview needs: all pages of a document, and the regions to mark on one of them. The chat
 * marks the passage a citation points at ([invoke]); the Extracted tab marks the box a field was read from ([forField]).
 *
 * Null when the document is gone, trashed or has no pages — the caller shows nothing rather
 * than a broken viewer.
 */
class GetDocumentPreviewUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val chunks: DocumentChunkRepository,
) {

    /** The preview with the passage of chunk [chunkId] marked on its page, when the chunk is known. */
    suspend operator fun invoke(documentId: String, chunkId: String? = null): DocumentPreview? =
        build(documentId) {
            val chunk = chunkId?.let { id ->
                runCatching { chunks.getForDocument(documentId) }.getOrNull()?.firstOrNull { it.id == id }
            }
            ({ page: DocumentPage ->
                if (chunk != null && chunk.pageNumber == page.pageNumber) PassageHighlighter.highlight(chunk.text, page.ocrBlocks) else emptyList()
            })
        }

    /**
     * The preview with [bbox] marked on [page] (1-based). No box means no marker; the preview still has every page, so the
     * viewer can open on [page] by its index in the result.
     */
    suspend fun forField(documentId: String, page: Int?, bbox: TextBounds?): DocumentPreview? =
        build(documentId) { { p: DocumentPage -> if (bbox != null && p.pageNumber == page) listOf(bbox) else emptyList() } }

    private suspend fun build(
        documentId: String,
        highlighter: suspend () -> (DocumentPage) -> List<TextBounds>,
    ): DocumentPreview? {
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data
            ?.takeUnless { it.isTrashed } ?: return null
        val pages = (documents.getDocumentPages(documentId) as? PamResult.Success)?.data
            ?.sortedBy { it.pageNumber }
            ?.takeIf { it.isNotEmpty() } ?: return null

        val highlightsOf = highlighter()
        return DocumentPreview(
            documentId = documentId,
            title = document.title,
            pages = pages.map { PreviewPage(it.pageNumber, it.imagePath, highlightsOf(it)) },
        )
    }
}
