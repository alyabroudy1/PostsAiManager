package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PageGeometryTest {

    @Test
    fun `fitted page maps normalised coordinates onto a letterboxed image`() {
        // A 100x200 image in a 200x200 box: scaled 1x, centred, 50 px of margin each side.
        val fit = FittedPage(200f, 200f, 100f, 200f)

        assertThat(fit.originX).isEqualTo(50f)
        assertThat(fit.shownWidth).isEqualTo(100f)
        assertThat(fit.left(TextBounds(0.5f, 0f, 1f, 1f))).isEqualTo(100f)
        assertThat(fit.x(0.25f)).isEqualTo(75f)
        assertThat(fit.y(0.25f)).isEqualTo(50f)
    }
}
