package com.postsaimanager.core.data.repository

import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.mapper.DocumentMapper

/**
 * The OCR a document already has, for a background reprocess.
 *
 * Reading a page image is the slow stage, and its result is stored (text, confidence and the
 * positioned blocks). Re-running it would change nothing a re-read needs, cost time and battery, and
 * could shift the candidate ids the extraction offers the model. So a reprocess reuses what is stored
 * whenever every page has it.
 */
internal object StoredOcr {

    /**
     * Each page with its stored OCR, or null unless *every* page has text and blocks stored: a single
     * missing page means the document was read by an older version, and it is read again as a whole so
     * the pages stay consistent with each other.
     */
    fun reuse(pages: List<DocumentPageEntity>, mapper: DocumentMapper): List<Pair<DocumentPageEntity, OcrResult?>>? {
        if (pages.isEmpty()) return null
        val reused = pages.map { page ->
            val text = page.ocrText ?: return null
            val blocks = mapper.pageToDomain(page).ocrBlocks
            if (blocks.isEmpty()) return null
            page to OcrResult(
                fullText = text,
                confidence = page.ocrConfidence ?: 0f,
                blocks = blocks,
                detectedLanguage = null,
            )
        }
        return reused
    }
}
