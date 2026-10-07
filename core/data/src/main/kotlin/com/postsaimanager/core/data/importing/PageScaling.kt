package com.postsaimanager.core.data.importing

import kotlin.math.max
import kotlin.math.roundToInt

/** The pixel size a source of a given size is rendered or decoded at: the longest side becomes the target, the shape is kept. */
object PageScaling {

    /** (width, height) in pixels, each at least 1. A zero or negative source size gives 1 by 1. */
    fun fit(sourceWidth: Int, sourceHeight: Int, longestSide: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1 to 1
        val scale = longestSide.toDouble() / max(sourceWidth, sourceHeight)
        return max(1, (sourceWidth * scale).roundToInt()) to max(1, (sourceHeight * scale).roundToInt())
    }

    /**
     * Like [fit] but never enlarges: a photo already smaller than the cap keeps its pixels (a PDF page, whose size is in points and
     * has no pixels of its own, uses [fit]).
     */
    fun fitDown(sourceWidth: Int, sourceHeight: Int, longestSide: Int): Pair<Int, Int> =
        if (max(sourceWidth, sourceHeight) <= longestSide) max(1, sourceWidth) to max(1, sourceHeight)
        else fit(sourceWidth, sourceHeight, longestSide)
}
