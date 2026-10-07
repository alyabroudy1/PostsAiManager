package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PageTextLinesTest {

    private fun b(l: Float, t: Float, r: Float, bt: Float) = TextBounds(l, t, r, bt)

    @Test
    fun `stored lines keep their order and boxes across blocks`() {
        val first = OcrLine("Hello world", b(0.1f, 0.1f, 0.6f, 0.15f))
        val second = OcrLine("Second", b(0.1f, 0.15f, 0.4f, 0.2f))
        val next = OcrLine("Next", b(0.1f, 0.3f, 0.3f, 0.35f))
        val lines = pageTextLines(
            listOf(
                OcrBlock("Hello world\nSecond", b(0.1f, 0.1f, 0.6f, 0.2f), 0.9f, lines = listOf(first, second)),
                OcrBlock("Next", next.bounds, 0.9f, lines = listOf(next)),
            ),
        )
        assertThat(lines.map { it.text }).containsExactly("Hello world", "Second", "Next").inOrder()
        assertThat(lines[2].bounds).isEqualTo(next.bounds)
    }

    @Test
    fun `a block without lines is split on newlines and its box divided vertically`() {
        val lines = pageTextLines(listOf(OcrBlock("one\ntwo", b(0.2f, 0.4f, 0.6f, 0.5f), 0.9f)))
        assertThat(lines.map { it.text }).containsExactly("one", "two").inOrder()
        assertThat(lines[0].bounds).isEqualTo(b(0.2f, 0.4f, 0.6f, 0.45f))
        assertThat(lines[1].bounds.top).isWithin(1e-6f).of(0.45f)
        assertThat(lines[1].bounds.bottom).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `blank lines are dropped and inner newlines become spaces`() {
        val lines = pageTextLines(
            listOf(OcrBlock("x", b(0f, 0f, 1f, 1f), 0.9f, lines = listOf(OcrLine("  ", b(0f, 0f, 1f, 0.1f)), OcrLine("a\nb", b(0f, 0.1f, 1f, 0.2f))))),
        )
        assertThat(lines.map { it.text }).containsExactly("a b")
    }

    @Test
    fun `a block with no text gives no lines`() {
        assertThat(pageTextLines(listOf(OcrBlock("", b(0f, 0f, 1f, 1f), 0.9f)))).isEmpty()
    }
}
