package com.postsaimanager.core.domain.extraction.candidates

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

    /** Characters OCR confuses with a digit inside numbers: `o`/`O` for 0 and `I`/`l` for 1. */
    private const val CONFUSABLE = "oOIl"

    fun isConfusable(c: Char) = c in CONFUSABLE

    /**
     * Repairs OCR character confusion in an IBAN that fails its checksum: tries every way of reading
     * the confusable letters after the country code as digits (fewest changes first) and returns the
     * upper-case IBAN only when the **mod-97 checksum then validates**; null otherwise (or when the
     * input has no confusable letters, is not a known country, or has the wrong length).
     *
     * A checksum is what makes this safe: a random misreading passes it with a chance of 1 in 97.
     */
    fun repair(input: String): String? {
        val raw = input.filter { !it.isWhitespace() }
        if (raw.length < 5) return null
        val expected = lengths[raw.substring(0, 2).uppercase()] ?: return null
        if (raw.length != expected) return null
        val positions = (2 until raw.length).filter { raw[it] in CONFUSABLE }
        if (positions.isEmpty() || positions.size > 10) return null
        val base = raw.uppercase()
        for (choice in (1 until (1 shl positions.size)).sortedBy { Integer.bitCount(it) }) {
            val sb = StringBuilder(base)
            for (b in positions.indices) {
                if (choice and (1 shl b) != 0) sb.setCharAt(positions[b], if (raw[positions[b]] in "oO") '0' else '1')
            }
            val candidate = sb.toString()
            if (validate(candidate).isValid) return candidate
        }
        return null
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
