package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RepetitionGuardTest {

    private val answer = "SENDER: Stadtwerke Musterstadt | company\nRECIPIENT: Erika Beispielfrau | person\nASKS: yes; pay — by 15.10.2026\n"

    @Test
    @DisplayName("a normal labelled answer is not a repeat")
    fun `no repeat`() {
        assertThat(RepetitionGuard.repeats(answer)).isFalse()
        assertThat(RepetitionGuard.trimmed(answer)).isEqualTo(answer)
    }

    @Test
    @DisplayName("a looping tail is recognised and cut after its first occurrence, what came before is kept")
    fun `loop`() {
        val loop = "REFERENCES: Kundennummer KD-0000-4711 — customer_no; IBAN DE00 0000 0000 0000 0000 00 — iban; "
        val text = answer + loop + loop + loop

        assertThat(RepetitionGuard.repeats(text)).isTrue()
        val cut = RepetitionGuard.trimmed(text)
        assertThat(cut).startsWith(answer)
        assertThat(cut.length).isLessThan(text.length)
        assertThat(cut).contains("Kundennummer KD-0000-4711")
        assertThat(RepetitionGuard.repeats(cut)).isFalse()
    }

    @Test
    @DisplayName("a short answer is never a repeat")
    fun `short`() {
        assertThat(RepetitionGuard.repeats("none\nnone\nnone\n")).isFalse()
    }
}
