package com.postsaimanager.core.domain.extraction.candidates

import java.util.Currency
import java.util.Locale

/**
 * Currency names as the platform's own locale data spells them ("Euro", "يورو", "Turkish lira"), in
 * the locales of [Hints.locales]. There is no word list of ours in here: the names come from
 * [Currency.getDisplayName], so any language the platform knows can be added by adding its locale.
 *
 * A name right after a number is a HINT that the number is money, one more way a number can show a
 * currency next to it besides a sign or an ISO code. It never decides anything else.
 */
internal object CurrencyNames {

    /** Lower-cased name to ISO code. A name shared by two currencies keeps the first (both are money). */
    private val byName: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (currency in Currency.getAvailableCurrencies()) {
            for (locale in Hints.locales()) {
                val name = currency.getDisplayName(locale).trim().lowercase(locale)
                if (name.length >= 3 && name != currency.currencyCode.lowercase() && name.all { it.isLetter() || it == ' ' || it == '-' }) {
                    out.putIfAbsent(name, currency.currencyCode)
                }
            }
        }
        out
    }

    private val MAX_WORDS = 3

    /**
     * When [after] (the text right behind a number) starts with a currency name, its ISO code and the
     * number of characters the name takes (leading space included); else null. The longest name wins.
     */
    fun codeAfter(after: String): Pair<String, Int>? {
        val m = Regex("^(\\s?)(\\p{L}[\\p{L}\\-]*(?: \\p{L}[\\p{L}\\-]*){0,${MAX_WORDS - 1}})").find(after) ?: return null
        val words = m.groupValues[2].split(' ')
        for (n in words.size downTo 1) {
            val candidate = words.take(n).joinToString(" ")
            val code = byName[candidate.lowercase()] ?: continue
            return code to (m.groupValues[1].length + candidate.length)
        }
        return null
    }
}

/**
 * The locales that hint at the language of a text. The extractor cannot know the document language
 * (the model reports it later), so it offers several: the device's, the languages the app is used in,
 * and a few widely used ones. Only ever used to read a name (a month, a currency), never to decide.
 */
internal object Hints {

    private val COMMON = listOf("de", "en", "fr", "es", "it", "tr", "ar", "pt", "nl", "pl", "ru")

    /** Device locale first, then [COMMON], each language once. */
    fun locales(): List<Locale> {
        val seen = HashSet<String>()
        return (listOf(Locale.getDefault()) + COMMON.map { Locale.forLanguageTag(it) }).filter { seen.add(it.language) }
    }
}
