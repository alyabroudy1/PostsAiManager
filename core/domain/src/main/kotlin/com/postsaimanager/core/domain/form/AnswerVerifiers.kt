package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.address.AddressFormats
import com.postsaimanager.core.domain.extraction.candidates.IbanValidator
import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.model.FormValueKind
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Code's checks of what the user answered in the fill conversation (the model only maps the free answer to a candidate; these
 * decide whether it is acceptable): each returns a verified, normalized value or the reason it was refused.
 *
 * Normalized forms: a choice is the option exactly as printed; a date is ISO `yyyy-MM-dd` (written for the form by
 * [FormValueFormatter]); a phone number keeps digits and its leading plus and common separators; an e-mail address has a
 * lower-cased domain; an IBAN is compact upper case with its country length and mod-97 checksum verified.
 */
object AnswerVerifiers {

    /** Accepted when [answer] names exactly one of [options] (as printed), folded like the quote verifier does (case, accents, Arabic spellings). */
    fun verifyChoice(answer: String, options: List<String>): Verification {
        val a = QuoteVerifier.fold(answer).trim()
        if (a.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        val folded = options.map { QuoteVerifier.fold(it).trim() }
        folded.indices.filter { folded[it] == a }.singleOrNull()?.let { return Verification.Accepted(options[it]) }
        val near = folded.indices.filter { i -> folded[i].length >= MIN_OPTION_CHARS && a.length >= MIN_OPTION_CHARS && (a.contains(folded[i]) || folded[i].contains(a)) }
        return when (near.size) {
            0 -> Verification.Rejected(Rejection.NOT_AN_OPTION)
            1 -> Verification.Accepted(options[near.single()])
            else -> Verification.Rejected(Rejection.AMBIGUOUS_OPTION)
        }
    }

    /**
     * Accepted when [text] is a date: numeric forms (`12.03.2019`, `12/3/19`, `2019-03-12`, with Arabic-Indic digits too), the order
     * of day and month taken from the form's [locale] unless a number above 12 decides it, or a written form of the locale
     * (`12. März 2019`). A two-digit year is read as the latest such year not in the future. The value is ISO.
     */
    fun verifyDate(text: String, locale: Locale, today: LocalDate = LocalDate.now()): Verification {
        val t = OcrText.normalizeChars(text).trim()
        if (t.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        val date = numericDate(t, locale, today) ?: writtenDate(t, locale)
        return if (date == null) Verification.Rejected(Rejection.NOT_A_DATE) else Verification.Accepted(date.toString())
    }

    fun verifyPhone(text: String): Verification {
        val t = OcrText.normalizeChars(text).trim()
        if (t.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        val digits = t.count { it.isDigit() }
        val shaped = t.all { it.isDigit() || it in PHONE_MARKS } && t.lastIndexOf('+') <= 0
        return if (shaped && digits in PHONE_DIGITS) Verification.Accepted(t.replace(Regex("\\s+"), " ")) else Verification.Rejected(Rejection.NOT_A_PHONE)
    }

    fun verifyEmail(text: String): Verification {
        val t = text.trim()
        if (t.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        if (!EMAIL.matches(t)) return Verification.Rejected(Rejection.NOT_AN_EMAIL)
        val at = t.lastIndexOf('@')
        return Verification.Accepted(t.substring(0, at) + "@" + t.substring(at + 1).lowercase())
    }

    fun verifyIban(text: String): Verification {
        val c = IbanValidator.compact(text)
        if (c.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        if (!IBAN_SHAPE.matches(c)) return Verification.Rejected(Rejection.NOT_AN_IBAN)
        val length = IbanValidator.lengths[c.substring(0, 2)]
        if (length != null && length != c.length) return Verification.Rejected(Rejection.NOT_AN_IBAN)
        return if (IbanValidator.hasValidChecksum(c)) Verification.Accepted(c) else Verification.Rejected(Rejection.BAD_IBAN_CHECKSUM)
    }

    /** A postcode of [countryIso2]'s format (any non-blank text when the country has no postcode data). */
    fun verifyPostcode(text: String, countryIso2: String?): Verification {
        val t = text.trim()
        if (t.isEmpty()) return Verification.Rejected(Rejection.EMPTY)
        val format = AddressFormats.of(countryIso2) ?: return Verification.Accepted(t)
        return if (format.postcodePattern == null || format.isPostcode(t)) Verification.Accepted(t) else Verification.Rejected(Rejection.NOT_A_POSTCODE)
    }

    /**
     * Checks [text] for a value of [kind]. When the field has [options] the answer must be one of them. Kinds with no shape to
     * check (a name, free text, an address) only must not be blank.
     */
    fun verify(
        kind: FormValueKind,
        text: String,
        locale: Locale = Locale.getDefault(),
        options: List<String> = emptyList(),
        countryIso2: String? = null,
        today: LocalDate = LocalDate.now(),
    ): Verification = when {
        options.isNotEmpty() -> verifyChoice(text, options)
        kind == FormValueKind.DATE -> verifyDate(text, locale, today)
        kind == FormValueKind.PHONE -> verifyPhone(text)
        kind == FormValueKind.EMAIL -> verifyEmail(text)
        kind == FormValueKind.IBAN -> verifyIban(text)
        kind == FormValueKind.POSTCODE -> verifyPostcode(text, countryIso2)
        text.isBlank() -> Verification.Rejected(Rejection.EMPTY)
        else -> Verification.Accepted(text.trim())
    }

    private fun numericDate(t: String, locale: Locale, today: LocalDate): LocalDate? {
        val m = NUMERIC.matchEntire(t) ?: return null
        val (a, b, c) = m.destructured
        val monthFirst = isMonthFirst(locale)
        return try {
            when {
                a.length == 4 -> LocalDate.of(a.toInt(), b.toInt(), c.toInt())
                c.length == 4 || c.length == 2 -> {
                    val year = if (c.length == 4) c.toInt() else pastYear(c.toInt(), today)
                    val dayFirst = when {
                        a.toInt() > 12 -> true
                        b.toInt() > 12 -> false
                        else -> !monthFirst
                    }
                    if (dayFirst) LocalDate.of(year, b.toInt(), a.toInt()) else LocalDate.of(year, a.toInt(), b.toInt())
                }
                else -> null
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun writtenDate(t: String, locale: Locale): LocalDate? {
        for (style in listOf(FormatStyle.LONG, FormatStyle.MEDIUM, FormatStyle.FULL)) {
            val f = DateTimeFormatter.ofLocalizedDate(style).withLocale(locale).withDecimalStyle(DecimalStyle.STANDARD)
            runCatching { return LocalDate.parse(t, f) }
        }
        return null
    }

    private fun isMonthFirst(locale: Locale): Boolean {
        val p = FormValueFormatter.datePattern(locale)
        val month = p.indexOf('M')
        val day = p.indexOf('d')
        return month in 0 until day || (day < 0 && month >= 0)
    }

    /** The latest year ending in [yy] that is not after [today]'s year. */
    private fun pastYear(yy: Int, today: LocalDate): Int {
        val thisCentury = today.year / 100 * 100 + yy
        return if (thisCentury <= today.year) thisCentury else thisCentury - 100
    }

    private const val MIN_OPTION_CHARS = 2
    private val PHONE_DIGITS = 6..15
    private val PHONE_MARKS = setOf('+', ' ', '-', '(', ')', '/', '.')
    private val NUMERIC = Regex("(\\d{1,4})[./\\-\\s]\\s?(\\d{1,2})[./\\-\\s]\\s?(\\d{1,4})")
    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@.]{2,}")
    private val IBAN_SHAPE = Regex("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}")
}
