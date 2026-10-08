package com.postsaimanager.core.domain.extraction.layout

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.OcrWord
import com.postsaimanager.core.model.TextBounds
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The avatar letter of a chat screenshot ("Z" in a circle) is a glyph, not the first letter of the name beside it. */
class AvatarGlyphTest {

    private fun word(text: String, left: Float, width: Float, top: Float = 0.10f, height: Float = 0.02f) =
        OcrWord(text, TextBounds(left, top, left + width, top + height))

    private fun line(words: List<OcrWord>) = OcrLine(
        text = words.joinToString(" ") { it.text },
        bounds = TextBounds(words.minOf { it.bounds.left }, words.minOf { it.bounds.top }, words.maxOf { it.bounds.right }, words.maxOf { it.bounds.bottom }),
        words = words,
    )

    private fun block(vararg lines: OcrLine) = OcrBlock(
        text = lines.joinToString("\n") { it.text },
        bounds = TextBounds(lines.minOf { it.bounds.left }, lines.minOf { it.bounds.top }, lines.maxOf { it.bounds.right }, lines.maxOf { it.bounds.bottom }),
        confidence = 0.9f,
        lines = lines.toList(),
    )

    @Test
    @DisplayName("a capital letter in its own, much taller box in front of a name is the avatar glyph and is cut off")
    fun `a much larger initial is stripped`() {
        val b = block(line(listOf(word("Z", 0.10f, 0.04f, top = 0.09f, height = 0.05f), word("Zahnarztpraxis", 0.16f, 0.30f))))

        val stripped = AvatarGlyph.strip(b)

        assertThat(stripped.text).isEqualTo("Zahnarztpraxis")
        assertThat(stripped.lines.single().words.map { it.text }).containsExactly("Zahnarztpraxis")
        assertThat(stripped.bounds.left).isAtLeast(0.16f)
    }

    @Test
    @DisplayName("a capital letter separated from the name by a wide gap is stripped, even at the same height")
    fun `a gap strips`() {
        val b = block(line(listOf(word("Z", 0.10f, 0.03f), word("Zahnarztpraxis", 0.25f, 0.30f))))

        assertThat(AvatarGlyph.strip(b).text).isEqualTo("Zahnarztpraxis")
    }

    @Test
    @DisplayName("the screenshot's layout: a near-square Z element, a gap wider than a word space (but narrower than 1.5 glyph widths), then the name")
    fun `the device screenshot layout is stripped`() {
        // A 1080 x 2400 chat screenshot, boxes normalised to the page: the avatar's initial is 0.040 wide and 0.015 high, the name 0.0167
        // high with 14 characters of 0.0204 each, and the name starts 0.050 after the initial. The earlier rule (a gap of 1.5 glyph
        // widths = 0.060) did not see this glyph.
        val avatar = word("Z", 0.060f, 0.040f, top = 0.0505f, height = 0.015f)
        val name = word("Zahnarztpraxis", 0.150f, 0.2856f, top = 0.050f, height = 0.0167f)
        val subtitle = word("Terminerinnerung", 0.150f, 0.30f, top = 0.075f, height = 0.0167f)
        val b = block(line(listOf(avatar, name)), line(listOf(subtitle)))

        val stripped = AvatarGlyph.strip(b)

        assertThat(stripped.text).isEqualTo("Zahnarztpraxis\nTerminerinnerung")
        assertThat(stripped.lines.first().words.map { it.text }).containsExactly("Zahnarztpraxis")
        assertThat(stripped.lines.last()).isSameInstanceAs(b.lines.last())
    }

    @Test
    @DisplayName("two initials in their own element are an avatar too")
    fun `two initials`() {
        val b = block(line(listOf(word("MK", 0.10f, 0.05f, height = 0.02f), word("Praxis", 0.22f, 0.13f))))

        assertThat(AvatarGlyph.strip(b).text).isEqualTo("Praxis")
    }

    @Test
    @DisplayName("a narrow capital (a bar or an I), a digit and a bullet are not avatars, whatever the gap")
    fun `not a near-square letter`() {
        val bar = block(line(listOf(word("I", 0.10f, 0.004f, height = 0.02f), word("Praxis", 0.25f, 0.13f))))
        val digit = block(line(listOf(word("1", 0.10f, 0.02f), word("Position", 0.25f, 0.17f))))
        val bullet = block(line(listOf(word("•", 0.10f, 0.02f), word("Position", 0.25f, 0.17f))))

        assertThat(AvatarGlyph.strip(bar)).isSameInstanceAs(bar)
        assertThat(AvatarGlyph.strip(digit)).isSameInstanceAs(digit)
        assertThat(AvatarGlyph.strip(bullet)).isSameInstanceAs(bullet)
    }

    @Test
    @DisplayName("only that line of a block loses its glyph; the other lines are untouched")
    fun `other lines stay`() {
        val b = block(
            line(listOf(word("Z", 0.10f, 0.03f), word("Zahnarztpraxis", 0.25f, 0.30f))),
            line(listOf(word("Terminerinnerung", 0.25f, 0.30f, top = 0.13f))),
        )

        assertThat(AvatarGlyph.strip(b).text).isEqualTo("Zahnarztpraxis\nTerminerinnerung")
    }

    @Test
    @DisplayName("an initial as tall as the name and one space before it is ordinary text and stays")
    fun `an ordinary initial stays`() {
        val b = block(line(listOf(word("J", 0.10f, 0.02f), word("Smith", 0.13f, 0.12f))))

        assertThat(AvatarGlyph.strip(b)).isSameInstanceAs(b)
    }

    @Test
    @DisplayName("a lower-case letter, a longer word, or a line of one word is never a glyph")
    fun `only a single capital`() {
        val lower = block(line(listOf(word("z", 0.10f, 0.03f, height = 0.05f), word("Zahnarztpraxis", 0.25f, 0.30f))))
        val word2 = block(line(listOf(word("Dr", 0.10f, 0.03f, height = 0.05f), word("Beispiel", 0.25f, 0.30f))))
        val alone = block(line(listOf(word("Z", 0.10f, 0.03f, height = 0.05f))))

        assertThat(AvatarGlyph.strip(lower)).isSameInstanceAs(lower)
        assertThat(AvatarGlyph.strip(word2)).isSameInstanceAs(word2)
        assertThat(AvatarGlyph.strip(alone)).isSameInstanceAs(alone)
    }

    @Test
    @DisplayName("a block read before words were kept cannot be told and is left as it is")
    fun `no words no change`() {
        val b = OcrBlock("Z Zahnarztpraxis", TextBounds(0.1f, 0.1f, 0.5f, 0.12f), 0.9f)

        assertThat(AvatarGlyph.strip(b)).isSameInstanceAs(b)
    }

    @Test
    @DisplayName("stripping twice changes nothing more")
    fun `idempotent`() {
        val once = AvatarGlyph.strip(block(line(listOf(word("Z", 0.10f, 0.03f), word("Zahnarztpraxis", 0.25f, 0.30f)))))

        assertThat(AvatarGlyph.strip(once)).isSameInstanceAs(once)
    }

    @Test
    @DisplayName("the layout and the candidates are read without the glyph: the sender candidate is the name alone")
    fun `the name candidate has no avatar letter`() {
        val head = block(line(listOf(word("Z", 0.10f, 0.03f, top = 0.05f), word("Zahnarztpraxis", 0.25f, 0.30f, top = 0.05f))))
        val pages = listOf(listOf(head))

        val layout = LetterLayoutAnalyzer.analyze(pages)
        assertThat(layout.allLines.map { it.text }).contains("Zahnarztpraxis")
        assertThat(layout.allLines.map { it.text }).doesNotContain("Z Zahnarztpraxis")

        val names = ExtractorCandidateSource().find(pages, layout).candidates.filter { it.kind == CandidateKind.NAME }
        assertThat(names.map { it.raw }).contains("Zahnarztpraxis")
        assertThat(names.none { it.raw.startsWith("Z ") }).isTrue()
        // Without the repair the glyph is part of the name.
        val unrepaired = CandidateExtractor.extract(pages, emptyMap()).candidates.filter { it.kind == CandidateKind.NAME }
        assertThat(unrepaired.any { it.raw.startsWith("Z ") }).isTrue()
    }
}
