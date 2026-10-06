package com.postsaimanager.core.domain.extraction.actions

import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A calendar date an action line states, with the time of day when the stored value has one (an appointment). */
data class ActionDate(val date: LocalDate, val time: LocalTime? = null)

/**
 * Reads the date inside a stored date field ("15.10.2026", "October 15, 2026", "Donnerstag, 12.11.2026 um 09:30") with the extraction's
 * own date finder (shape, and java.time's month names in any language), the same way the list reads a due date. A value that holds no
 * readable date (a period in words, "14 days after receipt") gives null: it is shown as printed under the line, never in the sentence.
 */
object ActionDates {

    fun read(text: String): ActionDate? =
        CandidateExtractor.extractFromText(text).candidates
            .asSequence()
            .filter { it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME }
            .mapNotNull { runCatching { parse(it.normalized) }.getOrNull() }
            .firstOrNull()

    private fun parse(normalized: String): ActionDate =
        if (normalized.contains('T')) LocalDateTime.parse(normalized).let { ActionDate(it.toLocalDate(), it.toLocalTime()) }
        else ActionDate(LocalDate.parse(normalized.take(ISO_DATE_CHARS)))

    private const val ISO_DATE_CHARS = 10
}
