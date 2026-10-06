package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.OcrWord
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.Test

class PageTextLayoutTest {

    private fun b(l: Float, t: Float, r: Float, bt: Float) = TextBounds(l, t, r, bt)

    // "Hello world" with word boxes; "Second" without; a second block far below: "Next".
    private val hello = OcrLine(
        "Hello world", b(0.1f, 0.1f, 0.6f, 0.15f),
        words = listOf(OcrWord("Hello", b(0.1f, 0.1f, 0.3f, 0.15f)), OcrWord("world", b(0.35f, 0.1f, 0.6f, 0.15f))),
    )
    private val second = OcrLine("Second", b(0.1f, 0.15f, 0.4f, 0.2f))
    private val next = OcrLine("Next", b(0.1f, 0.3f, 0.3f, 0.35f))
    private val blocks = listOf(
        OcrBlock("Hello world\nSecond", b(0.1f, 0.1f, 0.6f, 0.2f), 0.9f, lines = listOf(hello, second)),
        OcrBlock("Next", next.bounds, 0.9f, lines = listOf(next)),
    )
    private val layout = PageTextLayout(blocks)

    // "Hello world" 0..11, newline 11, "Second" 12..18, newline 18, "Next" 19..23.

    @Test
    fun `text joins lines and blocks with newlines`() {
        assertThat(layout.text).isEqualTo("Hello world\nSecond\nNext")
    }

    @Test
    fun `characters of a word are spread over that word's box and the gap over the gap`() {
        val rects = layout.selectionRects(TextSelection(2, 4))
        assertThat(rects).hasSize(1)
        assertThat(rects[0].left).isWithin(1e-4f).of(0.18f)
        assertThat(rects[0].right).isWithin(1e-4f).of(0.26f)
        // The space between the words covers the gap 0.3 .. 0.35.
        val space = layout.selectionRects(TextSelection(5, 6)).single()
        assertThat(space.left).isWithin(1e-4f).of(0.3f)
        assertThat(space.right).isWithin(1e-4f).of(0.35f)
        val world = layout.selectionRects(TextSelection(6, 11)).single()
        assertThat(world.left).isWithin(1e-4f).of(0.35f)
        assertThat(world.right).isWithin(1e-4f).of(0.6f)
    }

    @Test
    fun `a line without words spreads its characters evenly over the line box`() {
        val rect = layout.selectionRects(TextSelection(12, 15)).single()
        assertThat(rect.left).isWithin(1e-4f).of(0.1f)
        assertThat(rect.right).isWithin(1e-4f).of(0.25f)
        assertThat(rect.top).isEqualTo(0.15f)
    }

    @Test
    fun `words that do not match the line text fall back to even spreading`() {
        val odd = OcrLine("abcd", b(0f, 0f, 0.4f, 0.1f), words = listOf(OcrWord("zzz", b(0.3f, 0f, 0.4f, 0.1f))))
        val rect = PageTextLayout(listOf(OcrBlock("abcd", odd.bounds, 0.9f, lines = listOf(odd)))).selectionRects(TextSelection(0, 2)).single()
        assertThat(rect.right).isWithin(1e-4f).of(0.2f)
    }

    @Test
    fun `a block without lines is split on newlines and its box divided vertically`() {
        val old = PageTextLayout(listOf(OcrBlock("Ab Cd\nEf", b(0.1f, 0.5f, 0.5f, 0.7f), 0.9f)))

        assertThat(old.text).isEqualTo("Ab Cd\nEf")
        val second = old.selectionRects(TextSelection(6, 8)).single()
        assertThat(second.top).isWithin(1e-4f).of(0.6f)
        assertThat(second.bottom).isWithin(1e-4f).of(0.7f)
        assertThat(second.right).isWithin(1e-4f).of(0.5f)
        // The first line "Ab Cd" is 5 characters over 0.4: "Ab" is 0.1 .. 0.26.
        assertThat(old.selectionRects(TextSelection(0, 2)).single().right).isWithin(1e-4f).of(0.26f)
    }

    @Test
    fun `hit test finds a character and forgives a press near its border`() {
        assertThat(layout.hitTest(0.2f, 0.12f, 0.02f, 0.02f)).isEqualTo(2)
        // Just left of and above the text, within the touch radius.
        assertThat(layout.hitTest(0.09f, 0.095f, 0.03f, 0.03f)).isEqualTo(0)
        // Right of the last letter of "world".
        assertThat(layout.hitTest(0.62f, 0.12f, 0.03f, 0.03f)).isEqualTo(10)
    }

    @Test
    fun `hit test between lines picks the nearer line, and outside the radius finds nothing`() {
        // The gap between "Second" (bottom 0.2) and "Next" (top 0.3): 0.24 is nearer the first.
        val nearFirst = layout.hitTest(0.15f, 0.24f, 0.05f, 0.2f)!!
        assertThat(layout.text.substring(nearFirst, nearFirst + 1)).isIn(listOf("S", "e", "c", "o", "n", "d"))
        val nearSecond = layout.hitTest(0.15f, 0.27f, 0.05f, 0.2f)!!
        assertThat(nearSecond).isAtLeast(19)
        assertThat(layout.hitTest(0.9f, 0.9f, 0.03f, 0.03f)).isNull()
    }

    @Test
    fun `caret at a point is the nearest caret with no limit, and the ends are above and below everything`() {
        assertThat(layout.caretAt(0.29f, 0.12f)).isEqualTo(5)
        assertThat(layout.caretAt(0.0f, 0.12f)).isEqualTo(0)
        assertThat(layout.caretAt(0.9f, 0.12f)).isEqualTo(11)
        assertThat(layout.caretAt(0.5f, 0.0f)).isEqualTo(0)
        assertThat(layout.caretAt(0.5f, 0.99f)).isEqualTo(layout.text.length)
        // Below the first block's last line, nearer the next block: continues into it in reading order.
        assertThat(layout.caretAt(0.2f, 0.28f)).isAtLeast(19)
    }

    @Test
    fun `word expansion takes the run without whitespace, and a space picks a neighbour on its line`() {
        assertThat(layout.wordAt(2)).isEqualTo(TextSelection(0, 5))
        assertThat(layout.wordAt(8)).isEqualTo(TextSelection(6, 11))
        assertThat(layout.wordAt(5)).isEqualTo(TextSelection(0, 5))
        assertThat(layout.wordAt(13)).isEqualTo(TextSelection(12, 18))
        assertThat(layout.wordAt(11)).isNull()
        assertThat(PageTextLayout(emptyList()).wordAt(0)).isNull()
    }

    @Test
    fun `a range across lines and blocks has one rectangle per line segment and copies exactly`() {
        val selection = TextSelection(8, 21)

        assertThat(layout.textOf(selection)).isEqualTo("rld\nSecond\nNe")
        val rects = layout.selectionRects(selection)
        assertThat(rects).hasSize(3)
        assertThat(rects[0].right).isWithin(1e-4f).of(0.6f)
        assertThat(rects[1].left).isWithin(1e-4f).of(0.1f)
        assertThat(rects[2].top).isEqualTo(0.3f)
        assertThat(layout.textOf(layout.all()!!)).isEqualTo(layout.text)
    }

    @Test
    fun `handle anchors sit at the carets on the bottom of their lines`() {
        val (start, end) = layout.handleAnchors(TextSelection(2, 16))!!

        assertThat(start.x).isWithin(1e-4f).of(0.18f)
        assertThat(start.bottom).isEqualTo(0.15f)
        assertThat(end.top).isEqualTo(0.15f)
        assertThat(end.x).isWithin(1e-4f).of(0.1f + 0.3f * 4 / 6)
    }

    @Test
    fun `a page with no text has no selection`() {
        val empty = PageTextLayout(listOf(OcrBlock("  ", b(0f, 0f, 1f, 1f), 0.9f)))

        assertThat(empty.isEmpty).isTrue()
        assertThat(empty.all()).isNull()
        assertThat(empty.hitTest(0.5f, 0.5f, 1f, 1f)).isNull()
    }
}
