package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The title's party is the sender, or none: never the addressee (pass 27: "Letter · Maria Mustermann · ..."). */
class TitleSenderTest {

    private val layout = LetterLayoutAnalyzer.analyze(listOf(DeviceLetters.jobcenterBlocks))

    @Test
    @DisplayName("on the Jobcenter letter the sender stays in the title and the name in the address field is left out")
    fun `jobcenter`() {
        assertThat(TitleSender.of("Jobcenter Musterstadt", layout)).isEqualTo("Jobcenter Musterstadt")
        assertThat(TitleSender.of("Maria Mustermann", layout)).isNull()
        assertThat(TitleSender.of("  ", layout)).isNull()
        assertThat(TitleSender.of(null, layout)).isNull()
    }

    @Test
    @DisplayName("a title composed with the addressee as the sender has no party instead")
    fun `title`() {
        val title = TitleComposer.compose("official_letter", TitleSender.of("Maria Mustermann", layout), "Eingangsbestätigung Antrag")!!

        assertThat(title.title).doesNotContain("Maria")
        assertThat(title.args[1]).isEmpty()
    }
}
