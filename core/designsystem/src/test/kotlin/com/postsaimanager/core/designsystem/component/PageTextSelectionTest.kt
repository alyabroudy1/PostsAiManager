package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.TextRegion
import org.junit.jupiter.api.Test

class PageTextSelectionTest {

    private val regions = listOf(
        TextRegion("first", TextBounds(0.1f, 0.1f, 0.9f, 0.2f)),
        TextRegion("second", TextBounds(0.1f, 0.2f, 0.9f, 0.3f)),
        TextRegion("third", TextBounds(0.1f, 0.3f, 0.5f, 0.4f)),
    )

    @Test
    fun `copy text is the selected regions in reading order joined by newlines`() {
        val selection = PageTextSelection().toggle(2).toggle(0)

        assertThat(selection.text(regions)).isEqualTo("first\nthird")
    }

    @Test
    fun `toggle twice deselects, and select all covers every region`() {
        assertThat(PageTextSelection().toggle(1).toggle(1).count).isEqualTo(0)
        assertThat(PageTextSelection.all(regions.size).text(regions)).isEqualTo("first\nsecond\nthird")
    }

    @Test
    fun `a range selects both ends and everything between, whichever way it was dragged`() {
        assertThat(PageTextSelection().withRange(2, 1).ids).containsExactly(1, 2)
        assertThat(PageTextSelection().toggle(0).withRange(2, 2).ids).containsExactly(0, 2)
    }

    @Test
    fun `ids beyond the regions are ignored when copying`() {
        assertThat(PageTextSelection(setOf(1, 9)).text(regions)).isEqualTo("second")
    }

    @Test
    fun `hit test finds the region containing the point`() {
        assertThat(RegionHitTest.regionAt(regions, 0.5f, 0.25f)).isEqualTo(1)
        assertThat(RegionHitTest.regionAt(regions, 0.7f, 0.35f)).isNull()
    }

    @Test
    fun `slop lets a near miss hit, and the smallest of several wins`() {
        assertThat(RegionHitTest.regionAt(regions, 0.52f, 0.35f)).isNull()
        assertThat(RegionHitTest.regionAt(regions, 0.52f, 0.35f, slop = 0.03f)).isEqualTo(2)
        assertThat(RegionHitTest.regionAt(regions, 0.5f, 0.45f)).isNull()
        assertThat(RegionHitTest.regionAt(regions, 0.5f, 0.45f, slop = 0.06f)).isEqualTo(2)
        val nested = regions + TextRegion("tiny", TextBounds(0.4f, 0.12f, 0.5f, 0.14f))
        assertThat(RegionHitTest.regionAt(nested, 0.45f, 0.13f)).isEqualTo(3)
    }

    @Test
    fun `fitted page maps normalised coordinates onto a letterboxed image and back`() {
        // A 100x200 image in a 200x200 box: scaled 1x, centred, 50 px of margin each side.
        val fit = FittedPage(200f, 200f, 100f, 200f)

        assertThat(fit.originX).isEqualTo(50f)
        assertThat(fit.shownWidth).isEqualTo(100f)
        assertThat(fit.left(TextBounds(0.5f, 0f, 1f, 1f))).isEqualTo(100f)
        assertThat(fit.normalisedX(100f)).isEqualTo(0.5f)
        assertThat(fit.normalisedY(50f)).isEqualTo(0.25f)
    }

    @Test
    fun `unzoom inverts the zoom about the centre and the pan`() {
        // Scale 2 about the centre of a 200 box, panned 40: screen 140 -> (140 - 100 - 40) / 2 + 100 = 100.
        assertThat(unzoom(140f, 200f, 2f, 40f)).isEqualTo(100f)
        assertThat(unzoom(37f, 200f, 1f, 0f)).isEqualTo(37f)
    }
}
