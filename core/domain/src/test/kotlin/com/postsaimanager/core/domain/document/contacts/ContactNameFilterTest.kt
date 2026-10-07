package com.postsaimanager.core.domain.document.contacts

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContactNameFilterTest {

    private fun asks(printed: String, candidate: String) = ContactNameFilter.worthAsking(printed, candidate)

    @Test
    fun `a shared surname token keeps the candidate, whatever the salutation`() {
        assertThat(asks("Frau Müller", "Herr Müller")).isTrue()
        assertThat(asks("MÜLLER, Nadine", "Nadine Müller")).isTrue()
    }

    @Test
    fun `an initial is compatible with a name it starts`() {
        assertThat(asks("N. Müller", "Nadine Schmidt")).isTrue()
        assertThat(asks("Nadine", "N. Meier")).isTrue()
    }

    @Test
    fun `names with nothing in common are dropped`() {
        assertThat(asks("Karl Schmidt", "Nadine Müller")).isFalse()
        assertThat(asks("N. Müller", "Karl Schmidt")).isFalse()
    }

    @Test
    fun `a name without tokens narrows nothing`() {
        assertThat(asks("", "Nadine Müller")).isTrue()
        assertThat(asks("...", "Nadine Müller")).isTrue()
    }
}
