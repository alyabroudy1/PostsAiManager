package com.postsaimanager.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Blocks and lines share this normalisation, so a line box lands on the same page fractions its block's would. */
class OcrGeometryTest {

    @Test
    fun `pixels become page fractions`() {
        val b = OcrGeometry.normalise(100, 200, 300, 400, pageWidth = 1000f, pageHeight = 2000f)

        assertThat(b.left).isEqualTo(0.1f)
        assertThat(b.top).isEqualTo(0.1f)
        assertThat(b.right).isEqualTo(0.3f)
        assertThat(b.bottom).isEqualTo(0.2f)
    }

    @Test
    fun `a box poking out of the image is clamped to the page`() {
        val b = OcrGeometry.normalise(-20, -5, 1100, 2100, pageWidth = 1000f, pageHeight = 2000f)

        assertThat(b.left).isEqualTo(0f)
        assertThat(b.top).isEqualTo(0f)
        assertThat(b.right).isEqualTo(1f)
        assertThat(b.bottom).isEqualTo(1f)
    }
}
