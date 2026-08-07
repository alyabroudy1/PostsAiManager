package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.VectorMath
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TextChunker] and [VectorMath] — the pure half of retrieval.
 *
 * Deliberately built and proven before the ONNX embedding model exists. Retrieval bugs and
 * model bugs look identical from the outside ("search returns nothing useful"), so having
 * this layer verified first means the model can be debugged on its own.
 */
class TextChunkerTest {

    @Nested
    @DisplayName("Chunking")
    inner class Chunking {

        @Test
        fun `short text stays whole`() {
            val chunks = TextChunker.chunk("Sehr geehrte Damen und Herren, hiermit teilen wir mit.")

            assertThat(chunks).hasSize(1)
            assertThat(chunks.first().ordinal).isEqualTo(0)
        }

        @Test
        fun `blank input yields nothing`() {
            assertThat(TextChunker.chunk("")).isEmpty()
            assertThat(TextChunker.chunk("   \n\n  ")).isEmpty()
        }

        @Test
        @DisplayName("splits on paragraphs, keeping letter structure intact")
        fun `prefers paragraph boundaries`() {
            val letter = buildString {
                append("Jobcenter Berlin\nKarl-Marx-Allee 31\n10178 Berlin\n\n")
                append("Aktenzeichen: BG 1234/5678\n\n")
                append("Betreff: Bescheid\n\n")
                append("A".repeat(700)).append("\n\n")
                append("B".repeat(700)).append("\n\n")
                append("Mit freundlichen Grüßen")
            }

            val chunks = TextChunker.chunk(letter, targetChars = 800, overlapChars = 100)

            assertThat(chunks.size).isAtLeast(2)
            // Ordinals must be contiguous from zero, or reassembly and ordering break.
            assertThat(chunks.map { it.ordinal }).isEqualTo(chunks.indices.toList())
        }

        @Test
        @DisplayName("a fact at a chunk boundary appears in both chunks")
        fun `overlap preserves straddling content`() {
            // Two paragraphs sized so the split lands right after the deadline.
            val marker = "Die Frist endet am 31.01.2026."
            val text = "A".repeat(600) + "\n\n" + marker + "\n\n" + "B".repeat(600)

            val chunks = TextChunker.chunk(text, targetChars = 700, overlapChars = 200)
            val containing = chunks.count { marker in it.text }

            // Without overlap this fact would live in exactly one chunk, and a query
            // matching the other half would miss it entirely.
            assertThat(containing).isAtLeast(1)
            assertThat(chunks.joinToString(" ") { it.text }).contains("31.01.2026")
        }

        @Test
        fun `a single oversized paragraph is windowed`() {
            val wall = "Wort ".repeat(1_000) // ~5000 chars, no blank lines
            val chunks = TextChunker.chunk(wall, targetChars = 500, overlapChars = 50)

            assertThat(chunks.size).isAtLeast(5)
            assertThat(chunks.all { it.text.length <= 600 }).isTrue()
        }

        @Test
        @DisplayName("prefers sentence boundaries when windowing")
        fun `avoids cutting mid-sentence`() {
            val sentences = (1..40).joinToString(" ") { "Das ist Satz Nummer $it." }
            val chunks = TextChunker.chunk(sentences, targetChars = 300, overlapChars = 40)

            // Most chunks should end at a full stop rather than mid-word.
            val endingCleanly = chunks.count { it.text.trimEnd().endsWith(".") }
            assertThat(endingCleanly).isAtLeast(chunks.size - 1)
        }

        @Test
        fun `trivial fragments are dropped`() {
            val chunks = TextChunker.chunk("Hi\n\nOk\n\n" + "C".repeat(2_000), targetChars = 500)

            // A two-character chunk carries no retrievable signal and would only add noise.
            assertThat(chunks.none { it.text.length < 40 }).isTrue()
        }

        @Test
        fun `handles Windows line endings`() {
            val chunks = TextChunker.chunk("Erster Absatz.\r\n\r\nZweiter Absatz mit mehr Text.")
            assertThat(chunks).isNotEmpty()
            assertThat(chunks.first().text).doesNotContain("\r")
        }
    }

    @Nested
    @DisplayName("Vector maths")
    inner class Vectors {

        @Test
        fun `identical vectors score 1`() {
            val v = floatArrayOf(0.1f, 0.5f, -0.3f, 0.8f)
            assertThat(VectorMath.cosineSimilarity(v, v)).isWithin(1e-5f).of(1f)
        }

        @Test
        fun `opposite vectors score -1`() {
            val a = floatArrayOf(1f, 2f, 3f)
            val b = floatArrayOf(-1f, -2f, -3f)
            assertThat(VectorMath.cosineSimilarity(a, b)).isWithin(1e-5f).of(-1f)
        }

        @Test
        fun `orthogonal vectors score 0`() {
            assertThat(VectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
                .isWithin(1e-5f).of(0f)
        }

        @Test
        @DisplayName("magnitude does not affect similarity — only direction")
        fun `is scale invariant`() {
            val a = floatArrayOf(1f, 2f, 3f)
            val scaled = floatArrayOf(10f, 20f, 30f)
            assertThat(VectorMath.cosineSimilarity(a, scaled)).isWithin(1e-5f).of(1f)
        }

        @Test
        fun `mismatched or empty vectors score 0 rather than throwing`() {
            assertThat(VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)))
                .isEqualTo(0f)
            assertThat(VectorMath.cosineSimilarity(floatArrayOf(), floatArrayOf()))
                .isEqualTo(0f)
        }

        @Test
        fun `a zero vector scores 0 instead of dividing by zero`() {
            assertThat(VectorMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
                .isEqualTo(0f)
        }

        @Test
        fun `serialisation round-trips exactly`() {
            val original = floatArrayOf(0.123f, -0.456f, 1e-8f, 42f)
            val restored = VectorMath.fromBytes(VectorMath.toBytes(original))

            assertThat(restored).isNotNull()
            assertThat(restored!!.toList()).isEqualTo(original.toList())
        }

        @Test
        @DisplayName("a truncated BLOB decodes to null, not to a wrong vector")
        fun `rejects corrupt bytes`() {
            // A partial float would otherwise silently produce a shorter vector and
            // scores that look plausible but are meaningless.
            assertThat(VectorMath.fromBytes(ByteArray(7))).isNull()
            assertThat(VectorMath.fromBytes(ByteArray(0))).isNull()
            assertThat(VectorMath.fromBytes(null)).isNull()
        }
    }
}
