package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Where a block sits on the page, as fractions of page width and height.
 *
 * Normalised rather than pixels, because the same letter photographed at 8 MP and 12 MP
 * must describe the same layout. Anything comparing positions across documents — "the
 * reference block is where this sender always puts it" — needs a scale-free coordinate.
 */
@Serializable
data class TextBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A block of recognised text **and where it was**.
 *
 * The position is not decoration. A German business letter is laid out, not written
 * linearly: the sender sits top-left, the recipient in the window-envelope area below it,
 * the reference block (`Aktenzeichen`, `Ihr Zeichen`, `Datum`) to the right, the subject
 * centred above the body. Flattening that to one string destroys the only signal that
 * separates a reference number from a phone number, or an addressee from a person merely
 * mentioned.
 *
 * The pipeline previously discarded ML Kit's bounding boxes and kept only concatenated
 * text, which is why extraction once reported a sender organisation of
 * "563,00 Euro. Die Anpassung erfolgt automatisch" — a fragment of the body, read as
 * though it were adjacent to the sender block.
 */
@Serializable
data class OcrBlock(
    val text: String,
    val bounds: TextBounds,
    val confidence: Float,
    val language: String? = null,
)

/** Roughly where on the page a block sits. */
@Serializable
enum class LayoutZone {
    /** Sender letterhead in DIN 5008 — top of the page, left of centre. */
    HEADER_LEFT,

    /** Logos, and where some senders put the reference block instead. */
    HEADER_RIGHT,

    HEADER_CENTER,

    /** The window-envelope area: recipient address in a DIN 5008 letter. */
    ADDRESS_BLOCK,

    /** `Aktenzeichen`, `Ihr Zeichen`, `Datum` — right-hand column beside the address. */
    REFERENCE_BLOCK,

    /** Subject line and the letter body. */
    BODY,

    /** Signature, bank details, small print. */
    FOOTER,
}

/**
 * One page's recognised text with its layout preserved.
 *
 * [text] is kept alongside the blocks because keyword search, chunking and embedding all
 * want a plain reading of the page, and re-deriving it on every use would be wasteful and
 * would let the two drift apart.
 */
@Serializable
data class PageLayout(
    val pageNumber: Int,
    val blocks: List<OcrBlock>,
    val text: String,
)
