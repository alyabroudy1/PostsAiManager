package com.postsaimanager.core.domain.extraction.layout

/**
 * The shapes of an address block, by characters alone: a digit run, a word followed by a number, a short line of letters.
 * The one owner of these shapes: [LetterLayoutAnalyzer] finds the address field with them and the address reader finds the
 * lines of the address with the same ones, so a block that was zoned as an address is also read as one.
 *
 * No word of any language is here, so a German, English or Arabic block has the same shapes. What a shape *means* is decided
 * elsewhere (the address formats verify it, the model labels the words).
 */
object AddressShapes {

    /** A four to six digit run: a postcode in most countries. */
    val DIGIT_RUN = Regex("(?<!\\d)\\d{4,6}(?!\\d)")

    /** An alphanumeric postcode of the `SW1A 1AA` family. */
    val ALNUM_POSTCODE = Regex("\\b[A-Z]{1,2}\\d[A-Z\\d]?\\s?\\d[A-Z]{2}\\b")

    /** A word followed by a house number (`Musterstraße 12a`). */
    val STREET_NUMBER = Regex("\\p{L}[\\p{L}.\\-]*\\s*\\d{1,4}\\s?[a-zA-Z]?(?![\\d\\p{L}])")

    /** A house number followed by words (`10 Main Street`), for the countries that print the number first. */
    val NUMBER_STREET = Regex("^\\d{1,4}\\s?[A-Za-z]?(?:\\s?[-/]\\s?\\d{1,4}[A-Za-z]?)?[\\s,]+\\p{L}[\\p{L}.\\- ]*$")

    /** A word followed by a long number, possibly in groups (`Postfach 10 11 22`): a delivery point, not a house number. */
    val LONG_NUMBER_TAIL = Regex("\\p{L}[\\p{L}.\\- ]*\\s\\d[\\d ]{3,}\\d$")

    /** The largest vertical gap (page fraction) between two lines of one address block. */
    const val MAX_LINE_GAP = 0.045f

    /** The largest drift of the left edge (page fraction) between lines of one stack. */
    const val MAX_LEFT_DRIFT = 0.06f

    /** The largest gap from the "postcode place" line to a country line under it. */
    const val COUNTRY_GAP = 0.03f

    /** A short line holding a 4 to 6 digit run (postcode) or an alphanumeric postcode, with letters. */
    fun isPostcodeLine(t: String) =
        t.length in 4..45 && t.any { it.isLetter() } && (DIGIT_RUN.containsMatchIn(t) || ALNUM_POSTCODE.containsMatchIn(t))

    /** The line after "postcode place" by text: short, no digit, one to three words of letters. Any language. */
    fun isCountryText(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 3..25 || t.any { it.isDigit() } || t.last() in ",:;.") return false
        return t.split(Regex("\\s+")).size <= 3 && t.all { it.isLetter() || it in " -'" }
    }

    /** A line of letters only, no digit at all: a name, a department, a routing note or a city. */
    fun isWordOnly(text: String): Boolean = text.any { it.isLetter() } && text.none { it.isDigit() }
}
