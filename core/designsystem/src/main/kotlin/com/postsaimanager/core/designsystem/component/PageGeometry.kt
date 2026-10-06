package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.TextBounds

/**
 * Where a page image sits inside its (unzoomed) box when it is fitted with `ContentScale.Fit`, and the mapping between
 * the page's normalised 0..1 coordinates and box pixels. The single owner of that mapping: the highlight overlay, the
 * selection rectangles, the handles and hit-testing all go through it.
 */
internal class FittedPage(boxWidth: Float, boxHeight: Float, imageWidth: Float, imageHeight: Float) {
    val shownWidth: Float
    val shownHeight: Float
    val originX: Float
    val originY: Float

    init {
        val fit = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
        shownWidth = imageWidth * fit
        shownHeight = imageHeight * fit
        originX = (boxWidth - shownWidth) / 2f
        originY = (boxHeight - shownHeight) / 2f
    }

    /** A normalised x or y as box pixels, before any zoom. */
    fun x(normalised: Float): Float = originX + normalised * shownWidth
    fun y(normalised: Float): Float = originY + normalised * shownHeight

    fun left(b: TextBounds): Float = x(b.left)
    fun top(b: TextBounds): Float = y(b.top)

    /** The point (in box pixels, before any zoom) as page fractions; outside 0..1 when it is off the image. */
    fun normalisedX(x: Float): Float = (x - originX) / shownWidth
    fun normalisedY(y: Float): Float = (y - originY) / shownHeight
}

/** Undoes the preview's zoom: the page layer is scaled by [scale] about the box centre, then shifted by the offset. */
internal fun unzoom(point: Float, boxSize: Float, scale: Float, offset: Float): Float =
    (point - boxSize / 2f - offset) / scale + boxSize / 2f

/** The inverse of [unzoom]: where a point of the unzoomed page layer ends up on screen. */
internal fun zoomPoint(point: Float, boxSize: Float, scale: Float, offset: Float): Float =
    (point - boxSize / 2f) * scale + boxSize / 2f + offset
