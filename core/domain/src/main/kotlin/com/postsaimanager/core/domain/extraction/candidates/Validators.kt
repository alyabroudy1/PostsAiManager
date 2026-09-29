package com.postsaimanager.core.domain.extraction.candidates

import java.time.DateTimeException
import java.time.LocalDate

/**
 * IBAN validation: country length table (ISO 13616 registry) plus the mod-97 checksum.
 */
object IbanValidator {

    /** IBAN length per country, EU/EEA/CH/GB and the other registry countries. */
    val lengths: Map<String, Int> = mapOf(
        "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16, "BG" to 22,
        "BH" to 22, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28, "CZ" to 24, "DE" to 22,
        "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24, "FI" to 18, "FO" to 18, "FR" to 27,
        "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18, "GR" to 27, "GT" to 28, "HR" to 21, "HU" to 28,
        "IE" to 22, "IL" to 23, "IQ" to 23, "IS" to 26, "IT" to 27, "JO" to 30, "KW" to 30, "KZ" to 20,
        "LB" to 28, "LC" to 32, "LI" to 21, "LT" to 20, "LU" to 20, "LV" to 21, "MC" to 27, "MD" to 24,
        "ME" to 22, "MK" to 19, "MR" to 27, "MT" to 31, "MU" to 30, "NL" to 18, "NO" to 15, "PK" to 24,
        "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22, "SA" to 24, "SC" to 31,
        "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "ST" to 25, "SV" to 28, "TL" to 23, "TN" to 24,
        "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24, "XK" to 20,
    )

    /** Strips whitespace and upper-cases. */
    fun compact(s: String): String = s.filter { !it.isWhitespace() }.uppercase()

    /**
     * ISO 13616 mod-97 only (the check EntityExtractor has always done): 15..34 characters,
     * rotate the first four to the end, letters to numbers, remainder must be 1.
     */
    fun hasValidChecksum(iban: String): Boolean {
        if (iban.length !in 15..34) return false
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (ch in rearranged) {
            val chunk = when {
                ch.isDigit() -> (ch - '0').toString()
                ch in 'A'..'Z' -> (ch - 'A' + 10).toString()
                else -> return false
            }
            for (d in chunk) remainder = (remainder * 10 + (d - '0')) % 97
        }
        return remainder == 1
    }

    /** Full validation of a (possibly spaced) IBAN: shape, country, length, checksum. */
    fun validate(input: String): Validation {
        val iban = compact(input)
        if (iban.length < 5) return Validation.Invalid("too short")
        val country = iban.substring(0, 2)
        if (!country[0].isLetter() || !country[1].isLetter() || !iban[2].isDigit() || !iban[3].isDigit()) {
            return Validation.Invalid("not an IBAN shape")
        }
        val expected = lengths[country] ?: return Validation.Invalid("unknown IBAN country $country")
        if (iban.length != expected) {
            return Validation.Invalid("$country IBANs have $expected characters, found ${iban.length}")
        }
        return if (hasValidChecksum(iban)) Validation.Valid else Validation.Invalid("mod-97 checksum failed")
    }
}

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

    /** Currency symbol or word to ISO code, or null when the token is not a currency. */
    fun currencyOf(token: String?): String? = when (token?.trim()?.lowercase()) {
        null, "" -> null
        "eur", "euro", "€", "يورو" -> "EUR"
        "gbp", "£" -> "GBP"
        "usd", "us$", "$" -> "USD"
        "chf" -> "CHF"
        else -> null
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
}

/** Shape (and checksum where one exists) per reference subtype. */
object ReferenceValidator {

    private val KVNR = Regex("^[A-Z]\\d{9}$")
    private val RVNR = Regex("^\\d{8}[A-Z]\\d{3}$")

    fun validate(subtype: ReferenceSubtype, value: String): Validation {
        val v = value.trim()
        val compact = v.filter { !it.isWhitespace() }
        return when (subtype) {
            ReferenceSubtype.TAX_ID -> validateSteuerId(compact)
            ReferenceSubtype.INSURANCE_NO -> when {
                KVNR.matches(compact.uppercase()) ->
                    if (kvnrChecksumOk(compact.uppercase())) Validation.Valid
                    else Validation.Invalid("KVNR checksum failed")
                RVNR.matches(compact.uppercase()) -> Validation.Valid
                else -> Validation.Unchecked
            }
            ReferenceSubtype.TAX_NO -> {
                val digits = compact.count { it.isDigit() }
                if (compact.all { it.isDigit() || it == '/' || it == '-' } && digits in 10..13) Validation.Valid
                else Validation.Invalid("Steuernummer has 10 to 13 digits, found $digits")
            }
            ReferenceSubtype.BEITRAGSNUMMER ->
                if (compact.length == 9 && compact.all { it.isDigit() }) Validation.Valid
                else Validation.Invalid("Beitragsnummer has 9 digits")
            else -> Validation.Unchecked
        }
    }

    /**
     * Steuerliche Identifikationsnummer: 11 digits, first digit not 0, among the first ten
     * exactly one digit occurs 2 or 3 times (never 3 in a row), and an ISO 7064 MOD 11,10
     * check digit.
     */
    fun validateSteuerId(digits: String): Validation {
        if (digits.length != 11 || !digits.all { it.isDigit() }) return Validation.Invalid("Steuer-ID has 11 digits")
        if (digits[0] == '0') return Validation.Invalid("Steuer-ID does not start with 0")
        val body = digits.substring(0, 10)
        val counts = body.groupingBy { it }.eachCount()
        val repeated = counts.filter { it.value > 1 }
        if (repeated.size != 1 || repeated.values.first() > 3) return Validation.Invalid("digit pattern impossible for a Steuer-ID")
        if (body.windowed(3).any { it[0] == it[1] && it[1] == it[2] }) return Validation.Invalid("three equal digits in a row")
        var product = 10
        for (c in body) {
            var sum = ((c - '0') + product) % 10
            if (sum == 0) sum = 10
            product = (2 * sum) % 11
        }
        var check = 11 - product
        if (check == 10) check = 0
        return if (check == digits[10] - '0') Validation.Valid else Validation.Invalid("Steuer-ID check digit failed")
    }

    /**
     * Krankenversichertennummer: letter + 9 digits. The letter becomes two digits (A=01..Z=26),
     * the following eight digits are appended, digits are weighted 1,2,1,2,..., products
     * cross-summed, and the sum mod 10 is the tenth character.
     */
    fun kvnrChecksumOk(kvnr: String): Boolean {
        if (!KVNR.matches(kvnr)) return false
        val letter = kvnr[0] - 'A' + 1
        val seq = "%02d".format(letter) + kvnr.substring(1, 9)
        var sum = 0
        seq.forEachIndexed { i, c ->
            val p = (c - '0') * (if (i % 2 == 0) 1 else 2)
            sum += p / 10 + p % 10
        }
        return sum % 10 == kvnr[9] - '0'
    }
}

/**
 * Keeps OCR noise from becoming facts: TSE signatures, serial numbers, terminal and trace
 * ids, barcode digit runs. Applied to whole lines before any candidate is looked for.
 */
object NoiseFilter {

    /** Lines that *start* with one of these labels carry payment-terminal or fiscal noise. */
    private val NOISE_LABEL = Regex(
        "^\\s*(?:TSE\\b|Seriennummer|Serien-?Nr|Serial\\b|Signatur|Transaktionsnummer|Transaktions-?Nr|" +
            "Terminal-?ID|Trace-?Nr|Genehmigungs-?Nr|Beleg-?Nr|Signaturzähler|Sig\\.?-?Alg|Folge-?Nr|PAN\\b|" +
            "Gläubiger-?(?:ID|Identifikationsnummer)|Prüfwert|Zertifikat)",
        RegexOption.IGNORE_CASE,
    )

    private val IBAN_SHAPE = Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$")
    private val BLOB_CHARS = Regex("^[A-Za-z0-9+/=]+$")
    private val HEX = Regex("^[0-9a-fA-F]+$")

    fun isNoiseToken(token: String): Boolean {
        val t = token.trim(',', ';', ':', '(', ')', '"', '\'')
        if (t.length < 14) return false
        if (t.all { it.isDigit() }) return true // barcode / QR digit run
        if (t.length >= 32 && HEX.matches(t)) return true
        if (t.length < 20 || !BLOB_CHARS.matches(t)) return false
        if (IBAN_SHAPE.matches(t) && IbanValidator.lengths.containsKey(t.substring(0, 2))) return false
        // A long German word is letters only (capitalised or not); a signature or serial mixes
        // letters with digits or base64 symbols.
        val hasLetter = t.any { it.isLetter() }
        return hasLetter && (t.any { it.isDigit() } || t.any { it == '+' || it == '/' || it == '=' })
    }

    fun isNoiseLine(line: String): Boolean =
        NOISE_LABEL.containsMatchIn(line) || line.split(Regex("\\s+")).any { isNoiseToken(it) }
}
