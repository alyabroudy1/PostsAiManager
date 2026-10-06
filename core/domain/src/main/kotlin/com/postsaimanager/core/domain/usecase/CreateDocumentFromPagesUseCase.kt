package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentTitleCodes
import com.postsaimanager.core.model.SourceType
import javax.inject.Inject

/**
 * The one place a set of page images becomes a stored document that starts reading itself: what the scanner's result goes through
 * (and the debug import tool, which hands it pages without a camera). One document, one page per image, in the order given, then
 * the background reading is queued, so a scanned document is not left unsearchable until someone opens it.
 */
class CreateDocumentFromPagesUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val documentProcessor: DocumentProcessor,
) {

    /**
     * @param pageImages where each page's image is (a `file://` or `content://` URI string), page 1 first; must not be empty
     * @return the new document's id, or the repository's error. Nothing is queued when storing failed.
     */
    suspend operator fun invoke(pageImages: List<String>, sourceType: SourceType = SourceType.CAMERA): PamResult<String> {
        require(pageImages.isNotEmpty()) { "a document needs at least one page" }
        val now = System.currentTimeMillis()
        val documentId = UuidGenerator.generate()
        val pages = pageImages.mapIndexed { index, image ->
            DocumentPage(
                id = UuidGenerator.generate(),
                documentId = documentId,
                pageNumber = index + 1,
                imagePath = image,
                width = 0,
                height = 0,
            )
        }
        val document = Document(
            id = documentId,
            // The English text is only the fallback; the code and count are what the UI renders
            // in the user's language, until extraction gives the document a real title.
            title = "Scanned ${pageImages.size} page(s)",
            titleCode = DocumentTitleCodes.SCANNED_PAGES,
            titleArgs = listOf(pageImages.size.toString()),
            status = DocumentStatus.NEW,
            sourceType = sourceType,
            pageCount = pageImages.size,
            createdAt = now,
            modifiedAt = now,
        )
        return when (val result = documentRepository.createDocument(document, pages)) {
            is PamResult.Success -> {
                documentProcessor.enqueue(documentId)
                PamResult.Success(documentId)
            }
            is PamResult.Error -> PamResult.Error(result.error)
        }
    }
}
