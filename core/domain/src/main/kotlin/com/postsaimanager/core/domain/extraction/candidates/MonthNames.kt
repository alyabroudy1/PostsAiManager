package com.postsaimanager.core.domain.extraction.candidates

import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a month word ("September", "septembre", "Eylül", "سبتمبر", "сентября") with `java.time`'s own
 * month names, in the locales of [Hints.locales] plus the locales that go with the word's script.
 *
 * There is no month dictionary of ours. The document's language is not known when candidates are
 * found (the model reports it later), so several locales are asked, and the answer is a HINT: it is
 * used to write the date as ISO, and when no locale reads the word, or two locales read it as
 * different months, nothing is guessed and the date stays as printed.
 */
internal object MonthNames {

    private val cache = ConcurrentHashMap<String, Int>()

    /** The month (1..12) [word] names in every locale asked, or null when none reads it or they disagree. */
    fun monthOf(word: String): Int? {
        val w = word.trim().trimEnd('.').replace('’', '\'')
        if (w.length < 3) return null
        val key = w.lowercase(Locale.ROOT)
        val known = cache.getOrPut(key) { lookup(w) }
        return known.takeIf { it > 0 }
    }

    private fun lookup(word: String): Int {
        val months = HashSet<Int>()
        for (locale in Hints.locales() + scriptLocales(word)) {
            val w = word.lowercase(locale)
            for (month in Month.values()) if (reads(month, w, locale)) months += month.value
        }
        return months.singleOrNull() ?: 0
    }

    /** [w] is one of the month's names in the locale, or (three letters or more) the start of its full name: "Sept", "Okt", "Mär". */
    private fun reads(month: Month, w: String, locale: Locale): Boolean {
        val full = listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE).map { month.getDisplayName(it, locale).lowercase(locale) }
        val short = listOf(TextStyle.SHORT, TextStyle.SHORT_STANDALONE).map { month.getDisplayName(it, locale).lowercase(locale).trimEnd('.') }
        return w in full || w in short || (w.length >= 3 && full.any { it.startsWith(w) })
    }

    /** Locales that go with the writing system of [word]; the script tells which languages could have written it. */
    private fun scriptLocales(word: String): List<Locale> {
        val scripts = word.map { Character.UnicodeScript.of(it.code) }.toSet()
        val tags = buildList {
            if (Character.UnicodeScript.CYRILLIC in scripts) addAll(listOf("ru", "uk", "bg", "sr", "be"))
            if (Character.UnicodeScript.GREEK in scripts) add("el")
            if (Character.UnicodeScript.HEBREW in scripts) add("he")
            if (Character.UnicodeScript.ARABIC in scripts) addAll(listOf("ar", "ar-SY", "fa", "ur"))
            if (Character.UnicodeScript.DEVANAGARI in scripts) add("hi")
            if (Character.UnicodeScript.THAI in scripts) add("th")
            if (Character.UnicodeScript.HANGUL in scripts) add("ko")
        }
        return tags.map { Locale.forLanguageTag(it) }
    }
}
