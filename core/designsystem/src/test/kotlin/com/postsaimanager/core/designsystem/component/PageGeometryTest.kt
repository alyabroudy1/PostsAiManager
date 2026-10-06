package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PageGeometryTest {

    @Test
    fun `fitted page maps normalised coordinates onto a letterboxed image and back`() {
        // A 100x200 image in a 200x200 box: scaled 1x, centred, 50 px of margin each side.
        val fit = FittedPage(200f, 200f, 100f, 200f)

        assertThat(fit.originX).isEqualTo(50f)
        assertThat(fit.shownWidth).isEqualTo(100f)
        assertThat(fit.left(TextBounds(0.5f, 0f, 1f, 1f))).isEqualTo(100f)
        assertThat(fit.x(0.25f)).isEqualTo(75f)
        assertThat(fit.normalisedX(100f)).isEqualTo(0.5f)
        assertThat(fit.normalisedY(50f)).isEqualTo(0.25f)
    }

    @Test
    fun `unzoom inverts the zoom about the centre and the pan, and zoomPoint undoes it`() {
        // Scale 2 about the centre of a 200 box, panned 40: screen 140 -> (140 - 100 - 40) / 2 + 100 = 100.
        assertThat(unzoom(140f, 200f, 2f, 40f)).isEqualTo(100f)
        assertThat(unzoom(37f, 200f, 1f, 0f)).isEqualTo(37f)
        assertThat(zoomPoint(100f, 200f, 2f, 40f)).isEqualTo(140f)
        assertThat(zoomPoint(unzoom(63f, 300f, 3.5f, -20f), 300f, 3.5f, -20f)).isWithin(1e-3f).of(63f)
    }
}
