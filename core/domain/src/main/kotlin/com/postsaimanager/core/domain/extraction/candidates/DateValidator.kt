package com.postsaimanager.core.domain.extraction.candidates

import java.time.DateTimeException
import java.time.LocalDate

/** Real calendar dates, range checks against the letter date, and digit-swap detection. */
object DateValidator {

    /**
     * @param letterDate the letter's own date, when known. Without it a real calendar date is
     *   only UNCHECKED: nothing says whether 2066 is plausible.
     * @param pastYears how far before the letter a date may lie (1 for deadlines and due dates;
     *   more for billing periods, referenced letters and birth dates).
     */
    fun validate(
        year: Int,
        month: Int,
        day: Int,
        letterDate: LocalDate?,
        pastYears: Long = 1,
    ): Validation {
        val date = try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            return Validation.Invalid("not a calendar date: %02d.%02d.%04d".format(day, month, year))
        }
        if (letterDate == null) return Validation.Unchecked
        val lo = letterDate.minusYears(pastYears)
        val hi = letterDate.plusYears(3)
        if (date in lo..hi) return Validation.Valid
        val fix = suggestYear(year, lo.year, hi.year, letterDate.year)
        val hint = if (fix != null) "; did you mean $fix (digit swap)?" else ""
        return Validation.Invalid("$date is outside $lo..$hi around the letter date $letterDate$hint")
    }

    /** True when the date is a real calendar date. */
    fun isRealDate(year: Int, month: Int, day: Int): Boolean =
        try {
            LocalDate.of(year, month, day)
            true
        } catch (e: DateTimeException) {
            false
        }

    /**
     * A year within [lo]..[hi] that differs from [year] by one substituted digit or one
     * adjacent transposition (2066 -> 2026, 2062 -> 2026), nearest to [near]; null when none.
     */
    fun suggestYear(year: Int, lo: Int, hi: Int, near: Int): Int? {
        val s = year.toString()
        return (lo..hi)
            .filter { it != year && oneTypoApart(s, it.toString()) }
            .minByOrNull { kotlin.math.abs(it - near) }
    }

    private fun oneTypoApart(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val diff = a.indices.filter { a[it] != b[it] }
        return diff.size == 1 ||
            (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]])
    }
}
