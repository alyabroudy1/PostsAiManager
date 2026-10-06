package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PageTextRegionsTest {

    private fun block(text: String, left: Float, top: Float, lines: List<String> = emptyList()): OcrBlock {
        val bounds = TextBounds(left, top, left + 0.3f, top + 0.1f * maxOf(1, lines.size))
        return OcrBlock(
            text = text,
            bounds = bounds,
            confidence = 0.9f,
            lines = lines.mapIndexed { i, l -> OcrLine(l, TextBounds(left, top + 0.1f * i, left + 0.3f, top + 0.1f * (i + 1))) },
        )
    }

    @Test
    fun `a block with line boxes yields one region per line`() {
        val regions = PageTextRegions.of(listOf(block("a\nb", 0.1f, 0.1f, listOf("a", "b"))))

        assertThat(regions.map { it.text }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a block without line boxes yields the whole block`() {
        val regions = PageTextRegions.of(listOf(block("old block", 0.1f, 0.1f)))

        assertThat(regions.map { it.text }).containsExactly("old block")
    }

    @Test
    fun `regions follow the layout reading order and keep a column's lines together`() {
        val left = block("L1\nL2", 0.05f, 0.4f, listOf("L1", "L2"))
        val right = block("R1", 0.6f, 0.4f, listOf("R1"))
        val top = block("Top", 0.05f, 0.1f)

        val regions = PageTextRegions.of(listOf(right, left, top))

        assertThat(regions.map { it.text }).containsExactly("Top", "L1", "L2", "R1").inOrder()
    }

    @Test
    fun `blank text yields no regions`() {
        assertThat(PageTextRegions.of(listOf(block("  ", 0f, 0f)))).isEmpty()
        assertThat(PageTextRegions.of(emptyList())).isEmpty()
    }
}
