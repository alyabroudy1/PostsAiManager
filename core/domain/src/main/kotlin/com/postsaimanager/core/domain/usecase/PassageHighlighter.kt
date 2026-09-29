package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds

/**
 * Finds where a retrieved passage sits on its page, by comparing words.
 *
 * A chunk is cut from the page's flat OCR text with no memory of which block each word
 * came from, so the way back is by overlap: a block is part of the passage when enough of
 * its words occur in the chunk, or when it holds most of the chunk itself (a chunk cut from
 * the middle of one long block). Tokens are lower-cased letter/digit runs, so punctuation,
 * hyphenation of the line break and case never decide a match.
 *
 * Pure, so the matching is testable without a device, an image or a database.
 */
object PassageHighlighter {

    /** Share of a block's words that must be in the chunk for the block to count. */
    private const val BLOCK_COVERED = 0.6f

    /** Share of the chunk's words a single block must hold to count on its own. */
    private const val CHUNK_COVERED = 0.6f

    fun highlight(chunkText: String, blocks: List<OcrBlock>): List<TextBounds> {
        val chunkTokens = tokens(chunkText)
        if (chunkTokens.isEmpty() || blocks.isEmpty()) return emptyList()

        return blocks.filter { matches(chunkTokens, it) }.map { it.bounds }
    }

    private fun matches(chunkTokens: Set<String>, block: OcrBlock): Boolean {
        val blockTokens = tokens(block.text)
        if (blockTokens.isEmpty()) return false
        val shared = blockTokens.count { it in chunkTokens }
        if (shared == 0) return false

        // A single shared word ("Datum", "der") is coincidence, not evidence — unless the
        // block is only that one word and it is a number, which is specific enough.
        if (shared < 2) {
            return blockTokens.size == 1 && blockTokens.first().any { it.isDigit() }
        }
        val blockCovered = shared.toFloat() / blockTokens.size >= BLOCK_COVERED
        val chunkCovered = shared.toFloat() / chunkTokens.size >= CHUNK_COVERED
        return blockCovered || chunkCovered
    }

    private fun tokens(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
