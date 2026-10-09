package com.postsaimanager.core.domain.extraction.gemma

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.OfferedRow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The letter's own date that code adds when the answer names none: typed as a date, so a clock time is never it. */
class GemmaFoundValuesTest {

    private fun candidate(id: String, normalized: String, timeOnly: Boolean) = Candidate(
        id = id, kind = CandidateKind.DATETIME, raw = normalized, normalized = normalized, page = 1, bbox = null, evidence = normalized,
        attrs = if (timeOnly) mapOf("timeOnly" to "true") else emptyMap(),
    )

    private fun letterOf(vararg candidates: Candidate): Pair<GemmaLetter, OfferedCandidates> {
        val lines = candidates.mapIndexed { i, c -> GemmaLine("L$i", 1, LetterZone.LETTERHEAD.tag, 0f, i.toFloat(), c.raw) }
        val letter = GemmaLetter(
            lines,
            candidates.mapIndexed { i, c -> GemmaCandidate(c.id, c.kind, c.raw, c.normalized, "", "L$i") },
        )
        return letter to OfferedCandidates(candidates.map { OfferedRow(it, emptyList(), listOf(1)) })
    }

    @Test
    @DisplayName("a status-bar clock time in the header is no letter date, and does not hide the one real date next to it")
    fun `a time of day is not the letter date`() {
        val (letter, offered) = letterOf(candidate("D1", "T09:41", timeOnly = true), candidate("D2", "2026-10-14", timeOnly = false))

        assertThat(GemmaFoundValues().candidateOf(letter, offered)?.id).isEqualTo("D2")
    }

    @Test
    @DisplayName("with only a clock time in the header the letter has no found date")
    fun `only a time gives none`() {
        val (letter, offered) = letterOf(candidate("D1", "T09:41", timeOnly = true))

        assertThat(GemmaFoundValues().candidateOf(letter, offered)).isNull()
    }
}
