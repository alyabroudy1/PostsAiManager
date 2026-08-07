package com.postsaimanager.core.ai.embed

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [WordPieceTokenizer].
 *
 * Tokenization is the least visible way an embedding pipeline can be wrong: a subtly
 * different split still produces a plausible-looking vector, so search just gets quietly
 * worse with nothing to debug. These pin the behaviour that matters against a small
 * hand-built vocabulary.
 */
class WordPieceTokenizerTest {

    /** Deliberately small so expectations are readable. */
    private val vocab: Map<String, Int> = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]",
        "Die", "die", "Frist", "endet", "am", "Recht", "recht",
        "Wider", "##spruch", "##en", "Job", "##center", "Berlin",
        "31", ".", "01", "2026", ",", "!", "-",
        "unbekannt", "文", "字",
    ).withIndex().associate { (i, t) -> t to i }

    private val tokenizer = WordPieceTokenizer(vocab, doLowerCase = false)

    @Nested
    @DisplayName("Basic tokenization")
    inner class Basic {

        @Test
        fun `splits on whitespace`() {
            assertThat(tokenizer.tokenize("Die Frist endet am"))
                .containsExactly("Die", "Frist", "endet", "am").inOrder()
        }

        @Test
        fun `splits punctuation into its own tokens`() {
            assertThat(tokenizer.tokenize("31.01.2026"))
                .containsExactly("31", ".", "01", ".", "2026").inOrder()
        }

        @Test
        @DisplayName("case is preserved - German capitalisation carries meaning")
        fun `does not lowercase`() {
            // "Recht" (a right) and "recht" (quite/correct) are different words. An
            // English-tuned pipeline that lowercases by habit merges them.
            assertThat(tokenizer.tokenize("Recht recht"))
                .containsExactly("Recht", "recht").inOrder()
        }

        @Test
        fun `lowercasing is available when a model requires it`() {
            val lowering = WordPieceTokenizer(vocab, doLowerCase = true)
            assertThat(lowering.tokenize("Recht")).containsExactly("recht")
        }

        @Test
        @DisplayName("control characters are removed, joining the text either side")
        fun `removes control characters exactly as reference BERT does`() {
            // Reference BERT's _clean_text drops control characters WITHOUT substituting a
            // space, so "Die" + NUL + "Frist" collapses into one word and becomes [UNK].
            // Surprising, but diverging from the tokenizer the model was TRAINED with
            // yields a plausible-looking vector that is quietly wrong.
            assertThat(tokenizer.tokenize("Die\u0000Frist")).containsExactly("[UNK]")
        }

        @Test
        @DisplayName("tab and newline are whitespace, so they still separate words")
        fun `whitespace is not treated as a control character`() {
            // This is what OCR line breaks actually produce, and it must keep working.
            assertThat(tokenizer.tokenize("Die\tFrist\nendet"))
                .containsExactly("Die", "Frist", "endet").inOrder()
        }

        @Test
        fun `treats CJK characters as individual tokens`() {
            assertThat(tokenizer.tokenize("文字"))
                .containsExactly("文", "字").inOrder()
        }

        @Test
        fun `empty and blank input yield nothing`() {
            assertThat(tokenizer.tokenize("")).isEmpty()
            assertThat(tokenizer.tokenize("   \n\t ")).isEmpty()
        }
    }

    @Nested
    @DisplayName("WordPiece splitting")
    inner class Subwords {

        @Test
        fun `splits a compound into subword pieces`() {
            assertThat(tokenizer.tokenize("Widerspruch"))
                .containsExactly("Wider", "##spruch").inOrder()
        }

        @Test
        fun `handles multiple continuations`() {
            assertThat(tokenizer.tokenize("Widerspruchen"))
                .containsExactly("Wider", "##spruch", "##en").inOrder()
        }

        @Test
        @DisplayName("an unmatched word becomes [UNK] entirely, not a partial match")
        fun `does not emit partial matches`() {
            // "Jobzentrum" starts with the known piece "Job", but emitting "Job" + [UNK]
            // would assert a meaning the text does not have. Better to admit ignorance.
            assertThat(tokenizer.tokenize("Jobzentrum")).containsExactly("[UNK]")
        }

        @Test
        fun `an absurdly long word becomes UNK rather than being scanned`() {
            assertThat(tokenizer.tokenize("a".repeat(200))).containsExactly("[UNK]")
        }
    }

    @Nested
    @DisplayName("Encoding")
    inner class Encoding {

        @Test
        fun `wraps tokens in CLS and SEP`() {
            val encoding = tokenizer.encode("Die Frist", maxLength = 8)

            assertThat(encoding.inputIds[0]).isEqualTo(tokenizer.clsId.toLong())
            assertThat(encoding.inputIds[3]).isEqualTo(tokenizer.sepId.toLong())
        }

        @Test
        @DisplayName("padding is masked so it cannot affect the pooled vector")
        fun `masks padding`() {
            val encoding = tokenizer.encode("Die Frist", maxLength = 8)

            // [CLS] Die Frist [SEP] = 4 real positions.
            assertThat(encoding.attentionMask.toList())
                .isEqualTo(listOf(1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L))
            // Unmasked padding would pull every short sentence's embedding toward the
            // [PAD] vector, making similarity depend on padding length.
            assertThat(encoding.inputIds.drop(4).all { it == tokenizer.padId.toLong() }).isTrue()
        }

        @Test
        fun `always produces exactly maxLength positions`() {
            assertThat(tokenizer.encode("Die", maxLength = 16).length).isEqualTo(16)
            assertThat(tokenizer.encode("Die Frist endet am", maxLength = 16).length).isEqualTo(16)
        }

        @Test
        fun `truncates overlong input and still closes with SEP`() {
            val long = "Die Frist endet am ".repeat(50)
            val encoding = tokenizer.encode(long, maxLength = 10)

            assertThat(encoding.length).isEqualTo(10)
            assertThat(encoding.inputIds[0]).isEqualTo(tokenizer.clsId.toLong())
            // A truncated sequence that lost its [SEP] would be malformed model input.
            assertThat(encoding.inputIds[9]).isEqualTo(tokenizer.sepId.toLong())
            assertThat(encoding.attentionMask.all { it == 1L }).isTrue()
        }

        @Test
        fun `unknown words map to the UNK id`() {
            val encoding = tokenizer.encode("Jobzentrum", maxLength = 8)
            assertThat(encoding.inputIds[1]).isEqualTo(tokenizer.unkId.toLong())
        }
    }

    @Nested
    @DisplayName("Vocabulary parsing")
    inner class Vocabulary {

        @Test
        fun `assigns ids by line number`() {
            val parsed = WordPieceTokenizer.parseVocab(
                sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hallo"),
            )
            assertThat(parsed["[PAD]"]).isEqualTo(0)
            assertThat(parsed["hallo"]).isEqualTo(4)
        }

        @Test
        fun `tolerates Windows line endings`() {
            val parsed = WordPieceTokenizer.parseVocab(sequenceOf("[PAD]\r", "hallo\r"))
            assertThat(parsed).containsKey("hallo")
        }
    }
}
