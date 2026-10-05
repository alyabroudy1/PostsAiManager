package com.postsaimanager.core.domain.extraction.candidates

import java.util.Currency

/** A parsed amount. [currencyExplicit] is false when the currency was assumed (EUR). */
data class Money(val cents: Long, val currency: String, val currencyExplicit: Boolean) {
    /** "1284.50 EUR" (negative: "-5.00 EUR"). */
    fun canonical(): String {
        val abs = kotlin.math.abs(cents)
        val sign = if (cents < 0) "-" else ""
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} $currency"
    }
}

object AmountParser {

    private val GROUPED = Regex("^\\d{1,3}(?:[.,\\u2019'\\u202f]\\d{3})+$")

    /** ISO 4217 alphabetic codes, from the platform's own currency data (no word list of ours). */
    val isoCodes: Set<String> by lazy { Currency.getAvailableCurrencies().map { it.currencyCode }.toSet() }

    /** The ISO code of the widely used currency signs; any other sign is kept as itself. */
    private val SIGNS = mapOf(
        "€" to "EUR", "£" to "GBP", "$" to "USD", "¥" to "JPY", "₺" to "TRY", "₹" to "INR",
        "₽" to "RUB", "₩" to "KRW", "₪" to "ILS", "₫" to "VND", "₴" to "UAH", "₦" to "NGN",
        "฿" to "THB", "₱" to "PHP",
    )

    /**
     * The currency of a token: a currency sign (Unicode category Sc) or an upper-case ISO 4217 code;
     * null for anything else. A word is never read as a currency.
     */
    fun currencyOf(token: String?): String? {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return null
        SIGNS[t]?.let { return it }
        if (t.length == 1 && Character.getType(t[0]) == Character.CURRENCY_SYMBOL.toInt()) return t
        return t.takeIf { it.length == 3 && it in isoCodes }
    }

    /**
     * Parses a printed number to cents. The last separator followed by exactly two digits is
     * the decimal mark ("1.284,50", "1,284.50", "142.80"); a separator followed by three digits
     * is a thousands mark ("1.284", "€ 1,284"); "35,-" is a whole amount.
     *
     * @return null when the number is not a well-formed amount.
     */
    fun parse(number: String, currencyToken: String?, negative: Boolean = false): Money? {
        var n = number.trim()
        val explicit = currencyOf(currencyToken)
        val currency = explicit ?: "EUR"
        n = n.removeSuffix("--").removeSuffix("-").removeSuffix(",").removeSuffix(".")
        val lastSep = n.indexOfLast { it == '.' || it == ',' }
        val cents: Long
        if (lastSep < 0) {
            if (!n.all { it.isDigit() }) return null
            cents = (n.toLongOrNull() ?: return null) * 100
        } else {
            val after = n.length - lastSep - 1
            if (after == 2) {
                val intPart = n.substring(0, lastSep)
                if (intPart.isEmpty()) return null
                if (intPart.any { !it.isDigit() } && !GROUPED.matches(intPart)) return null
                val whole = intPart.filter { it.isDigit() }.toLongOrNull() ?: return null
                cents = whole * 100 + n.substring(lastSep + 1).toInt()
            } else if (after == 3 && GROUPED.matches(n)) {
                cents = (n.filter { it.isDigit() }.toLongOrNull() ?: return null) * 100
            } else {
                return null
            }
        }
        if (cents > 99_999_999_999L) return null
        return Money(if (negative) -cents else cents, currency, explicit != null)
    }
}

/**
 * Consistency of a net / VAT / gross triple. Exposed for reuse; the extractor applies it to
 * amounts whose labels say net, VAT and gross.
 */
object AmountConsistency {
    fun netPlusVatEqualsGross(netCents: Long, vatCents: Long, grossCents: Long): Boolean =
        kotlin.math.abs(netCents + vatCents - grossCents) <= 1

    /** Tax rates worth recognising, in percent (standard, reduced and super-reduced rates in use around the world, up to 27). */
    private val COMMON_RATES = listOf(
        0.0, 2.0, 2.5, 3.0, 3.8, 4.0, 5.0, 5.5, 6.0, 7.0, 7.7, 8.0, 8.1, 9.0, 10.0, 12.0, 13.0, 14.0, 15.0, 16.0,
        17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 27.0,
    )

    /**
     * The language-neutral net + VAT = gross check: [net] + [vat] = [gross] within one cent, and
     * [vat] / [net] within rounding of a common rate between 0 and 27 percent. Both amounts positive.
     * Which of the three is called what in the letter is never looked at.
     */
    fun isNetVatGross(netCents: Long, vatCents: Long, grossCents: Long): Boolean {
        if (netCents <= 0 || vatCents <= 0 || !netPlusVatEqualsGross(netCents, vatCents, grossCents)) return false
        val rate = 100.0 * vatCents / netCents
        // one cent of rounding in the VAT moves the rate by 100 / net percentage points
        val tolerance = 0.05 + 100.0 / netCents
        return COMMON_RATES.any { kotlin.math.abs(rate - it) <= tolerance }
    }
}
