package com.postsaimanager.core.data.repository

import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.extraction.layout.PlainTextBlocks
import com.postsaimanager.core.model.OcrBlock

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
     *
     * @param requireWordBoxes also null unless every line carries the boxes of its words (the elements ML Kit returns, kept since the page
     *   preview's text selection). A reading that tells an avatar glyph from a letter by the elements' boxes ([com.postsaimanager.core.domain.extraction.layout.AvatarGlyph])
     *   needs them, and a page stored before they were kept has none: it is read again once, and stored with them.
     */
    fun reuse(pages: List<DocumentPageEntity>, mapper: DocumentMapper, requireWordBoxes: Boolean = false): List<Pair<DocumentPageEntity, OcrResult?>>? {
        if (pages.isEmpty()) return null
        val reused = pages.map { page ->
            // A page whose text the user corrected is read from that text (plain lines), whatever blocks it has stored.
            if (isUserText(page)) return@map page to userEdited(page)
            val text = page.ocrText ?: return null
            val blocks = mapper.pageToDomain(page).ocrBlocks
            if (blocks.isEmpty()) return null
            if (requireWordBoxes && !hasWordBoxes(blocks)) return null
            page to OcrResult(
                fullText = text,
                confidence = page.ocrConfidence ?: 0f,
                blocks = blocks,
                detectedLanguage = null,
            )
        }
        return reused
    }

    /** Whether the user corrected [page]'s text: it is read as it stands and never replaced by a recognition. */
    fun isUserText(page: DocumentPageEntity): Boolean = page.textSource == "USER"

    /**
     * The pictures a reading is shown: a page whose text the user corrected has none, because the picture still holds the old words and
     * the model would read them instead of the person's text.
     */
    fun picturesFor(pages: List<DocumentPageEntity>): List<String> = pages.filterNot(::isUserText).map { it.imagePath }

    /**
     * What a reading is given for a page whose text the user corrected: their text, and its plain lines as the blocks (the person typed
     * words, not positions, so the page has no layout beyond running text).
     */
    fun userEdited(page: DocumentPageEntity): OcrResult {
        val text = page.ocrText.orEmpty()
        return OcrResult(fullText = text, confidence = 1f, blocks = PlainTextBlocks.of(text), detectedLanguage = null)
    }

    /** Whether every block holds lines and every line holds the boxes of its words. */
    private fun hasWordBoxes(blocks: List<OcrBlock>): Boolean =
        blocks.all { block -> block.lines.isNotEmpty() && block.lines.all { it.words.isNotEmpty() } }
}
