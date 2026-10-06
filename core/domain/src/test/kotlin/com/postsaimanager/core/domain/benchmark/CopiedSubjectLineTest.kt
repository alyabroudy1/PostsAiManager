package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import org.junit.jupiter.api.Test

/** A subject line the model was asked to copy must really be a line of the letter: the kind of document it echoed is not. */
class CopiedSubjectLineTest {

    private val ocr: String by lazy {
        BenchmarkFixtures.load().docs.first { it.first.key == "N2-kfz-verlaengerung-2p" }.second.pages
            .joinToString("\n") { page -> page.blocks.joinToString("\n") { it.text } }
    }

    @Test
    fun `one word answered with the kind of document is not a subject line of the car-insurance letter`() {
        assertThat(QuoteVerifier.verifyCopiedLine("LETTER", ocr)).isNull()
    }

    @Test
    fun `the printed subject line of that letter is accepted`() {
        assertThat(QuoteVerifier.verifyCopiedLine("Ihre Kfz-Versicherung – Beitrag ab 01.01.2027", ocr)).isNotNull()
    }
}
