package com.postsaimanager.core.domain.form

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/** How a stored value is written on a form: dates in the form's locale, an IBAN in groups of four. Pure formatting, no meaning. */
object FormValueFormatter {

    private val YEAR = Regex("y+")
    private val BIDI_MARKS = Regex("[‎‏؜]")

    /** The locale's short date pattern with a four-digit year (`dd.MM.yyyy`, `dd/MM/yyyy`, `M/d/yyyy`). */
    fun datePattern(locale: Locale): String {
        val short = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        return YEAR.replace(short, "yyyy")
    }

    fun formatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(datePattern(locale), locale).withDecimalStyle(DecimalStyle.STANDARD)

    fun date(date: LocalDate, locale: Locale): String = BIDI_MARKS.replace(formatter(locale).format(date), "")

    /** [value] as a date in the form's format when it is an ISO date, otherwise as it is. */
    fun date(value: String, locale: Locale): String =
        runCatching { date(LocalDate.parse(value.trim()), locale) }.getOrDefault(value)

    /** [iban] in groups of four when it is a compact IBAN, otherwise as it is. */
    fun iban(iban: String): String {
        val compact = iban.filter { !it.isWhitespace() }
        return if (compact.length in 15..34) compact.chunked(4).joinToString(" ") else iban
    }
}
