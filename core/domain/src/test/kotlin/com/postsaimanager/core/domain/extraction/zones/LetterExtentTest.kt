package com.postsaimanager.core.domain.extraction.zones

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LetterExtentTest {

    @Test
    fun `a few short lines are described by their count, not judged`() {
        val text = "Erinnerung: Termin am Montag um 9 Uhr.\n\nBitte pünktlich sein.\nIhre Praxis"
        assertThat(LetterExtent.describe(text)).isEqualTo("The letter holds 3 lines of text, 12 words.")
    }

    @Test
    fun `the same words in any language and a single line read in the singular`() {
        assertThat(LetterExtent.describe("موعدك غدًا")).isEqualTo("The letter holds 1 line of text, 2 words.")
        assertThat(LetterExtent.describe("Hallo")).isEqualTo("The letter holds 1 line of text, 1 word.")
    }

    @Test
    fun `an empty page has no lines and no words`() {
        assertThat(LetterExtent.describe("\n  \n")).isEqualTo("The letter holds 0 lines of text, 0 words.")
    }
}
