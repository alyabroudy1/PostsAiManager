package com.postsaimanager.core.data.repository

import com.postsaimanager.core.model.TextBounds

/**
 * Turns a recognised box in image pixels into the page-relative [TextBounds] stored with the OCR.
 * One owner for blocks and lines alike, so both are normalised and clamped the same way.
 */
internal object OcrGeometry {

    /** The box as fractions of the page, each clamped to 0..1 (ML Kit can report a box slightly outside the image). */
    fun normalise(left: Int, top: Int, right: Int, bottom: Int, pageWidth: Float, pageHeight: Float): TextBounds =
        TextBounds(
            left = (left / pageWidth).coerceIn(0f, 1f),
            top = (top / pageHeight).coerceIn(0f, 1f),
            right = (right / pageWidth).coerceIn(0f, 1f),
            bottom = (bottom / pageHeight).coerceIn(0f, 1f),
        )
}
