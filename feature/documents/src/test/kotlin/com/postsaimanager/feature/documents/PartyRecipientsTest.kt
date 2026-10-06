package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PartyRecipientsTest {

    @Test
    fun `the Me profile's name, in any case or order, reads You`() {
        assertThat(PartyRecipients.of("erika  MUSTERMANN", "Erika Mustermann")).isEqualTo(PagesRecipient.You)
        assertThat(PartyRecipients.of("Mustermann, Erika", "Erika Mustermann")).isEqualTo(PagesRecipient.You)
        assertThat(PartyRecipients.of("Frau Erika Mustermann", "Erika Mustermann")).isEqualTo(PagesRecipient.You)
    }

    @Test
    fun `another name or no Me profile stays named`() {
        assertThat(PartyRecipients.of(" Max Mustermann ", "Erika Mustermann")).isEqualTo(PagesRecipient.Named("Max Mustermann"))
        assertThat(PartyRecipients.of("Erika Mustermann", "Erika Mustermann-Berger")).isEqualTo(PagesRecipient.Named("Erika Mustermann"))
        assertThat(PartyRecipients.of("Erika Mustermann", null)).isEqualTo(PagesRecipient.Named("Erika Mustermann"))
    }

    @Test
    fun `sameName is the shared folded comparison`() {
        assertThat(PartyRecipients.sameName("Erika  Mustermann", "erika mustermann")).isTrue()
        assertThat(PartyRecipients.sameName("Erika", "Maria")).isFalse()
    }
}
