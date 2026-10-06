package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.TextRegion

/**
 * Where a page image sits inside its (unzoomed) box when it is fitted with `ContentScale.Fit`, and the mapping between
 * the page's normalised 0..1 coordinates and box pixels. The single owner of that mapping: the highlight overlay, the
 * selectable regions and tap hit-testing all go through it.
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

    fun left(b: TextBounds): Float = originX + b.left * shownWidth
    fun top(b: TextBounds): Float = originY + b.top * shownHeight

    /** The point (in box pixels, before any zoom) as page fractions; outside 0..1 when it is off the image. */
    fun normalisedX(x: Float): Float = (x - originX) / shownWidth
    fun normalisedY(y: Float): Float = (y - originY) / shownHeight
}

/** Undoes the preview's zoom: the page layer is scaled by [scale] about the box centre, then shifted by the offset. */
internal fun unzoom(point: Float, boxSize: Float, scale: Float, offset: Float): Float =
    (point - boxSize / 2f - offset) / scale + boxSize / 2f

/** Which of a page's regions are selected, by index into its reading-ordered list. */
internal data class PageTextSelection(val ids: Set<Int> = emptySet()) {

    val count: Int get() = ids.size

    fun toggle(id: Int): PageTextSelection = copy(ids = if (id in ids) ids - id else ids + id)

    /** This selection plus every region from [from] to [to] inclusive, in either direction. */
    fun withRange(from: Int, to: Int): PageTextSelection =
        copy(ids = ids + (minOf(from, to)..maxOf(from, to)))

    /** The selected regions' text in reading order (the regions list already is), one per line. */
    fun text(regions: List<TextRegion>): String =
        ids.sorted().filter { it in regions.indices }.joinToString("\n") { regions[it].text }

    companion object {
        fun all(regionCount: Int) = PageTextSelection((0 until regionCount).toSet())
    }
}

/** Finds the region under a tap. */
internal object RegionHitTest {

    /**
     * The index of the region containing the page point ([x], [y], normalised), each region grown by [slop] on every side so
     * thin lines can be hit by a fingertip. When several match (neighbouring lines grown into each other) the smallest wins.
     */
    fun regionAt(regions: List<TextRegion>, x: Float, y: Float, slop: Float = 0f): Int? =
        regions.indices
            .filter { i ->
                val b = regions[i].bounds
                x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop
            }
            .minByOrNull { regions[it].bounds.let { b -> b.width * b.height } }
}
