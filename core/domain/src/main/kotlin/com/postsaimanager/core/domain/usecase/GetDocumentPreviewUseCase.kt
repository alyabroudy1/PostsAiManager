package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.TextBounds
import javax.inject.Inject

/** One page of a [DocumentPreview]: the image to show and what to mark on it. */
data class PreviewPage(
    val pageNumber: Int,
    val imagePath: String,
    /** Normalised regions holding the cited passage; empty on every page but the cited one. */
    val highlights: List<TextBounds> = emptyList(),
)

data class DocumentPreview(
    val documentId: String,
    val title: String,
    val pages: List<PreviewPage>,
)

/**
 * Everything the chat's in-place page preview needs: all pages of a document, and, when the
 * cited chunk is known, the regions of its page that hold that passage.
 *
 * Null when the document is gone, trashed or has no pages — the caller shows nothing rather
 * than a broken viewer.
 */
class GetDocumentPreviewUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val chunks: DocumentChunkRepository,
) {

    suspend operator fun invoke(documentId: String, chunkId: String? = null): DocumentPreview? {
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data
            ?.takeUnless { it.isTrashed } ?: return null
        val pages = (documents.getDocumentPages(documentId) as? PamResult.Success)?.data
            ?.sortedBy { it.pageNumber }
            ?.takeIf { it.isNotEmpty() } ?: return null

        val chunk = chunkId?.let { id ->
            runCatching { chunks.getForDocument(documentId) }.getOrNull()?.firstOrNull { it.id == id }
        }

        return DocumentPreview(
            documentId = documentId,
            title = document.title,
            pages = pages.map { page ->
                val highlights = if (chunk != null && chunk.pageNumber == page.pageNumber) {
                    PassageHighlighter.highlight(chunk.text, page.ocrBlocks)
                } else {
                    emptyList()
                }
                PreviewPage(page.pageNumber, page.imagePath, highlights)
            },
        )
    }
}
