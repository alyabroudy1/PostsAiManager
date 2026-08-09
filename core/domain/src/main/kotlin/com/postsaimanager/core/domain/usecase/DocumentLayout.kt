package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.LayoutZone
import com.postsaimanager.core.model.OcrBlock

/**
 * Turns positioned OCR blocks into something a model can reason about.
 *
 * ### Why reading order is not sorting by y
 *
 * A DIN 5008 letter has two things side by side: the recipient's address on the left and
 * the reference block — `Aktenzeichen`, `Ihr Zeichen`, `Datum` — on the right. Sorting
 * blocks purely by vertical position interleaves them line by line, producing
 *
 * ```
 * Frau                      Aktenzeichen: BG 1234/5678
 * Aylin Mustermann          Ihr Zeichen: WS-2026-0142
 * ```
 * → `Frau · Aktenzeichen: BG 1234/5678 · Aylin Mustermann · Ihr Zeichen: WS-2026-0142`
 *
 * — where the recipient's name now sits between two reference numbers. That is exactly the
 * kind of adjacency that made regex extraction report a sender organisation of
 * "563,00 Euro. Die Anpassung erfolgt automatisch".
 *
 * So blocks are grouped into horizontal **bands** first, and within a band read
 * left-to-right, keeping each column intact.
 *
 * Pure, so every layout decision is testable without a camera or a model.
 */
object DocumentLayout {

    /**
     * Blocks in the order a person would read them.
     *
     * Two blocks are in the same band when they overlap vertically by more than
     * [BAND_OVERLAP]. Overlap rather than a fixed row height, because block heights vary
     * enormously — a one-line date and a six-line address can start at the same y.
     */
    fun readingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks

        val remaining = blocks.sortedBy { it.bounds.top }.toMutableList()
        val ordered = mutableListOf<OcrBlock>()

        while (remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            val band = mutableListOf(first)

            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (verticalOverlap(first, candidate) > BAND_OVERLAP) {
                    band += candidate
                    iterator.remove()
                }
            }

            ordered += band.sortedBy { it.bounds.left }
        }
        return ordered
    }

    /**
     * Fraction of the shorter block's height that the two share vertically.
     *
     * Relative to the shorter block on purpose: a tall address block and a single-line date
     * beside it share only a little of the address's height but nearly all of the date's,
     * and they are plainly side by side.
     */
    private fun verticalOverlap(a: OcrBlock, b: OcrBlock): Float {
        val top = maxOf(a.bounds.top, b.bounds.top)
        val bottom = minOf(a.bounds.bottom, b.bounds.bottom)
        val shared = bottom - top
        if (shared <= 0f) return 0f
        val shorter = minOf(a.bounds.height, b.bounds.height)
        return if (shorter <= 0f) 0f else shared / shorter
    }

    /**
     * Where a block sits, in DIN 5008 terms.
     *
     * Bands are approximate and deliberately so — the point is to give a model a hint about
     * a block's likely purpose, not to enforce a template. A letter that puts its reference
     * block on the left is still readable; it just loses the hint.
     */
    fun zoneOf(block: OcrBlock): LayoutZone {
        val y = block.bounds.centerY
        val x = block.bounds.centerX

        return when {
            y < HEADER_END -> when {
                x < LEFT_EDGE -> LayoutZone.HEADER_LEFT
                x > RIGHT_EDGE -> LayoutZone.HEADER_RIGHT
                else -> LayoutZone.HEADER_CENTER
            }
            // The window-envelope band. Left is the addressee; right is where the sender
            // puts what they want quoted back at them.
            y < ADDRESS_END -> if (x < CENTER) {
                LayoutZone.ADDRESS_BLOCK
            } else {
                LayoutZone.REFERENCE_BLOCK
            }
            y > FOOTER_START -> LayoutZone.FOOTER
            else -> LayoutZone.BODY
        }
    }

    /**
     * The page as the model should see it.
     *
     * Each block is labelled with its zone and its position, so the model can weigh "this
     * looks like a name" against "this is in the address block" instead of guessing from
     * wording alone. Coordinates are included as coarse percentages — enough to tell
     * side-by-side from stacked, without inviting false precision from a rounded box.
     *
     * Costs roughly thirty tokens per block. On a one-page letter that is a fair trade for
     * the difference between knowing where the reference number was and not.
     */
    fun describe(blocks: List<OcrBlock>): String {
        if (blocks.isEmpty()) return ""

        return readingOrder(blocks).joinToString("\n") { block ->
            val zone = zoneOf(block).name.lowercase().replace('_', ' ')
            val x = (block.bounds.left * 100).toInt()
            val y = (block.bounds.top * 100).toInt()
            val text = block.text.lines().joinToString(" / ") { it.trim() }.trim()
            "[$zone @ ${x}%,${y}%] $text"
        }
    }

    /** Plain reading of the page, columns kept intact. */
    fun plainText(blocks: List<OcrBlock>): String =
        readingOrder(blocks).joinToString("\n") { it.text }

    /** Blocks in one zone — for asking "what is in the address block?" directly. */
    fun inZone(blocks: List<OcrBlock>, zone: LayoutZone): List<OcrBlock> =
        readingOrder(blocks).filter { zoneOf(it) == zone }

    private const val BAND_OVERLAP = 0.5f

    // Fractions of page height. DIN 5008 puts the address field roughly 45–90 mm from the
    // top of a 297 mm page, which is about 15–30%; these are widened to tolerate scans that
    // are cropped or photographed at an angle.
    private const val HEADER_END = 0.14f
    private const val ADDRESS_END = 0.34f
    private const val FOOTER_START = 0.88f

    private const val LEFT_EDGE = 0.40f
    private const val CENTER = 0.50f
    private const val RIGHT_EDGE = 0.60f
}
