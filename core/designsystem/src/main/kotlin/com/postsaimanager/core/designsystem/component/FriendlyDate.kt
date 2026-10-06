package com.postsaimanager.core.designsystem.component

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How a short display date reads, decided from the date and today alone (so a fixed clock tests it).
 *
 * @property day how near the date is to today.
 * @property showYear true when the date is not in today's year; the year is then part of the text.
 */
data class FriendlyDate(val day: Day, val showYear: Boolean) {

    enum class Day { TODAY, TOMORROW, PAST, LATER }

    companion object {
        /** The reading of [date] seen on [today]: today and tomorrow get their own word, anything else a dated reading. */
        fun of(date: LocalDate, today: LocalDate): FriendlyDate {
            val day = when (ChronoUnit.DAYS.between(today, date)) {
                0L -> Day.TODAY
                1L -> Day.TOMORROW
                in Long.MIN_VALUE..-1L -> Day.PAST
                else -> Day.LATER
            }
            return FriendlyDate(day, showYear = date.year != today.year)
        }

        /**
         * The date with a month name in the user's own order and language ("5 Nov", "5. Nov.", "٥ نوفمبر"), never
         * digits alone ("5/10" is day-first or month-first by guess). The year is added only when [showYear].
         * The one place the app's short display dates are formatted.
         */
        fun format(date: LocalDate, showYear: Boolean, locale: Locale): String {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (showYear) "dMMMy" else "dMMM")
            return DateTimeFormatter.ofPattern(pattern, locale).format(date)
        }

        /** [format] for [date] seen on [today], in the screen's locale. */
        @Composable
        fun text(date: LocalDate, today: LocalDate = LocalDate.now()): String {
            val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
            return format(date, date.year != today.year, locale)
        }
    }
}
