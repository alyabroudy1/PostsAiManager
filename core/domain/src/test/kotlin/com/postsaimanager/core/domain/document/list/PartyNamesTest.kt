package com.postsaimanager.core.domain.document.list

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PartyNamesTest {

    @Test
    fun `case, accents, spacing and punctuation are ignored`() {
        assertThat(PartyNames.names("erika  MUSTERMANN", "Erika Mustermann")).isTrue()
        assertThat(PartyNames.names("Müller, Jörg", "Jorg Muller")).isTrue()
    }

    @Test
    fun `the printed order may differ from the profile's`() {
        assertThat(PartyNames.names("Mustermann, Erika", "Erika Mustermann")).isTrue()
    }

    @Test
    fun `the full name inside a longer line matches`() {
        assertThat(PartyNames.names("Frau Dr. Erika Mustermann, Hauptstr. 1", "Erika Mustermann")).isTrue()
    }

    @Test
    fun `a longer profile name does not match a shorter printed one`() {
        assertThat(PartyNames.names("Erika Mustermann", "Erika Mustermann-Berger")).isFalse()
    }

    @Test
    fun `a conjunction is never split on`() {
        assertThat(PartyNames.names("Max und Erika Mustermann", "Erika Mustermann")).isTrue() // contiguous full name
        assertThat(PartyNames.names("Erika und Max Mustermann", "Erika Mustermann")).isFalse()
    }

    @Test
    fun `blank names never match`() {
        assertThat(PartyNames.names("", "Erika Mustermann")).isFalse()
        assertThat(PartyNames.names("Erika Mustermann", "  ")).isFalse()
    }

    @Test
    fun `sameName compares folded tokens`() {
        assertThat(PartyNames.sameName(" Erika  Mustermann", "erika mustermann")).isTrue()
        assertThat(PartyNames.sameName("Erika", "Maria")).isFalse()
        assertThat(PartyNames.sameName("", "")).isFalse()
    }
}
