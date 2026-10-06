package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextRegion

/**
 * The pieces of a page's text a person can select in the preview, in the order they read them.
 *
 * Blocks are ordered by [DocumentLayout.readingOrder] (the one owner of that ordering); inside a block the lines keep the order
 * the recogniser gave them. Granularity is per block: a block with stored line boxes contributes one region per line, one
 * without (read before lines were kept) contributes the whole block. Purely geometric, no reading of the text.
 */
object PageTextRegions {

    fun of(blocks: List<OcrBlock>): List<TextRegion> =
        DocumentLayout.readingOrder(blocks).flatMap { block ->
            val lines = block.lines.filter { it.text.isNotBlank() }
            if (lines.isNotEmpty()) {
                lines.map { TextRegion(it.text, it.bounds) }
            } else if (block.text.isNotBlank()) {
                listOf(TextRegion(block.text, block.bounds))
            } else {
                emptyList()
            }
        }
}
