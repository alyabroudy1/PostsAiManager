package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import java.time.LocalDate

/**
 * The calendar date inside a stored date field ("15.10.2026", "October 15, 2026", "2026-10-15 09:00").
 *
 * A field keeps the date as the letter printed it, so a row that wants to compare it with today has to
 * read it again. That is done by the extraction's own date finder (shape, and java.time's month names
 * in any language), not by a second parser here. A value with no readable date (a period in words, a
 * date kept as printed) gives null and the row falls back to another date.
 */
object PrintedDateReader {

    fun read(text: String): LocalDate? =
        CandidateExtractor.extractFromText(text).candidates
            .asSequence()
            .filter { it.kind == CandidateKind.DATE || it.kind == CandidateKind.DATETIME }
            .mapNotNull { runCatching { LocalDate.parse(it.normalized.take(ISO_DATE_CHARS)) }.getOrNull() }
            .firstOrNull()

    private const val ISO_DATE_CHARS = 10
}
