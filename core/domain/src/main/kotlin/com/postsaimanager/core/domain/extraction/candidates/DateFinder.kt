package com.postsaimanager.core.domain.extraction.candidates

import java.time.LocalDate

/**
 * Dates and times by shape: ISO and numeric dates, long-form dates (a day number, a word and a year in
 * any order, the word read afterwards as a month from java.time's names) and clock times. A time right
 * behind a date makes it a DATETIME. Relative deadlines are not found here: a period given in words
 * ("within one month") is quoted by the model as a rule and verified against the letter afterwards
 * (see extraction.v2.RelativePeriod); no phrase in any language decides that one exists.
 */
internal object DateFinder : CandidateFinder {

    /** With a letter date given, a date may lie this far before it; the same for every date, no label decides. */
    private const val GIVEN_LETTER_PAST_YEARS = 10L

    val ISO_DATE = Regex("(?<![\\d-])(\\d{4})-(\\d{2})-(\\d{2})(?!\\d|-\\d)")
    val NUM_DATE = Regex("(?<![\\d.,/])(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d|[.,]\\d)")

    // Long-form dates by shape: a day number, a word and a four-digit year, in any order. Whether the
    // word is a month, and which, is read afterwards from java.time's names (MonthNames) as a hint.
    // A short word between the parts ("de", "of", "le", "di") is allowed, whatever it says.
    private const val ORDINAL = "\\p{L}{0,2}[.\u00BA\u00B0\u00AA]?"
    private const val WORD = "\\p{L}{3,12}"
    private const val SMALL = "(?:\\p{L}{1,3}\\s+)?"

    /** 26 September 2026, 26. Sept. 2026, 1er septembre 2026, 26 de septiembre de 2026, 26 Eyl\u00FCl 2026, 26 \u0633\u0628\u062A\u0645\u0628\u0631 2026. */
    private val DAY_FIRST = Regex("(?<![\\d.,/])(\\d{1,2})$ORDINAL\\s*$SMALL($WORD)\\.?,?\\s+$SMALL(\\d{4})(?!\\d)")

    /** October 10, 2026 (the comma after the day is optional). */
    private val WORD_FIRST = Regex("(?<![\\p{L}\\d])($WORD)\\.?\\s+(\\d{1,2})$ORDINAL(,?)\\s+(\\d{4})(?!\\d)")

    /** 2026 September 26. */
    private val YEAR_FIRST = Regex("(?<![\\d.,/])(\\d{4})\\s+($WORD)\\.?\\s+(\\d{1,2})[.\u00BA\u00B0\u00AA]?(?!\\d)")

    /**
     * A time right behind a date: hours, `:` or `.`, minutes, optional seconds; at most two short
     * words before it ("um", "at", "a las") or an `@`. No word is required and none is looked for.
     */
    private val TIME_AFTER = Regex("^(?:\\s*[,;])?\\s+(?:(?:\\p{L}{1,6}\\.?\\s+){0,2}|@\\s*)(\\d{1,2})([:.])(\\d{2})(?::\\d{2})?(?![.,:]?\\d)")

    /** A time of its own: `hh:mm` anywhere, `hh.mm` only when a short token follows. */
    private val TIME_ONLY = Regex("(?<![\\p{L}\\d:.\\-/_])(\\d{1,2})([:.])(\\d{2})(?::\\d{2})?(?![_/\\-]|[.,:]?\\d)")

    /** A short token right behind a time: a unit such as "Uhr", "h", "pm". Any language; only its shape is looked at. */
    private val SHORT_TOKEN_AFTER = Regex("^\\s?\\p{L}{1,3}(?![\\p{L}])")

    /** Money or a percentage right behind a figure that could be read as a time ("12.50 EUR"): then it is not a time. */
    private val MONEY_OR_PERCENT_AFTER = Regex("^\\s?(?:[%\u2030]|${CurrencyShape.CURRENCY})(?![\\p{L}])")

    private val WHITE = Regex("\\s+")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)

        fun emit(range: IntRange, y: Int, mo: Int, d: Int) {
            if (!mask.free(range)) return
            var end = range.last + 1
            var normalized = "%04d-%02d-%02d".format(y, mo, d)
            var kind = CandidateKind.DATE
            // A time right behind the date, by shape: hh:mm or hh.mm. A dot time is dropped when money or a
            // percentage follows it ("12.11.2026 12.50 EUR"): that figure is not a time.
            val tm = TIME_AFTER.find(text.substring(end))
            if (tm != null) {
                val hh = tm.groupValues[1].toInt()
                val mm = tm.groupValues[3].toInt()
                val sepDot = tm.groupValues[2] == "."
                val rest = text.substring(end + tm.value.length)
                if (hh < 24 && mm < 60 && !(sepDot && MONEY_OR_PERCENT_AFTER.containsMatchIn(rest))) {
                    end += tm.value.length
                    normalized += "T%02d:%02d".format(hh, mm)
                    kind = CandidateKind.DATETIME
                }
            }
            val r = range.first until end
            mask.add(r)
            out += ctx.draft(line, r, kind, text.substring(r.first, end).trim(), normalized)
        }

        /** The shape is a date but no locale reads the word as a month (or they disagree): kept as printed, quoted not read. */
        fun emitRaw(range: IntRange) {
            if (!mask.free(range)) return
            mask.add(range)
            val raw = text.substring(range.first, range.last + 1).trim().replace(WHITE, " ")
            out += ctx.draft(line, range, CandidateKind.DATE, raw, raw, attrs = mapOf("unnormalized" to "true"))
        }

        fun plausible(day: Int, year: Int) = day in 1..31 && year in 1900..2199

        /** A long-form date: read the month word with java.time's names when possible, else keep it as printed. */
        fun longDate(range: IntRange, day: Int, word: String, year: Int, rawAllowed: Boolean) {
            if (!plausible(day, year) || !mask.free(range)) return
            val month = MonthNames.monthOf(word)
            if (month != null) emit(range, year, month, day) else if (rawAllowed) emitRaw(range)
        }
        for (m in ISO_DATE.findAll(text)) emit(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        for (m in NUM_DATE.findAll(text)) {
            val yy = m.groupValues[3]
            val y = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
            emit(m.range, y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        // Day, word, year. The word may be a month in any language; when none reads it the date stays as printed.
        for (m in DAY_FIRST.findAll(text)) longDate(m.range, m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toInt(), rawAllowed = true)
        // Word, day, year: kept as printed only with the comma of "October 10, 2026", else a figure pair after any word would be a date.
        for (m in WORD_FIRST.findAll(text)) {
            longDate(m.range, m.groupValues[2].toInt(), m.groupValues[1], m.groupValues[4].toInt(), rawAllowed = m.groupValues[3].isNotEmpty())
        }
        for (m in YEAR_FIRST.findAll(text)) longDate(m.range, m.groupValues[3].toInt(), m.groupValues[2], m.groupValues[1].toInt(), rawAllowed = false)
        // A time on its own: hh:mm anywhere, hh.mm only with a short token behind it ("9.30 Uhr") and no money or percent sign.
        for (m in TIME_ONLY.findAll(text)) {
            val hh = m.groupValues[1].toInt()
            val mm = m.groupValues[3].toInt()
            if (hh >= 24 || mm >= 60 || !mask.free(m.range)) continue
            if (m.groupValues[2] == ".") {
                val rest = text.substring(m.range.last + 1)
                if (MONEY_OR_PERCENT_AFTER.containsMatchIn(rest) || !SHORT_TOKEN_AFTER.containsMatchIn(rest)) continue
            }
            mask.add(m.range)
            out += ctx.draft(
                line, m.range, CandidateKind.DATETIME, m.value, "T%02d:%02d".format(hh, mm),
                validation = Validation.Valid, attrs = mapOf("timeOnly" to "true"),
            )
        }
    }

    private fun parseYmd(normalized: String): Triple<Int, Int, Int>? {
        if (normalized.length < 10 || normalized[0] == 'T') return null
        val p = normalized.substring(0, 10).split('-')
        if (p.size < 3) return null // a date kept as printed has no ISO form
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    /**
     * Calendar validity, and the range around the letter date when the caller supplied one. Code no
     * longer picks a letter date from a label: with none given every real date stays UNCHECKED here
     * and the verifier checks the others against the date the model chose as the letter date.
     * No label decides the window; it is the same generous window for every date.
     */
    fun validate(drafts: List<Draft>, letter: LocalDate?) {
        for (d in drafts) {
            if (d.c.kind != CandidateKind.DATE && d.c.kind != CandidateKind.DATETIME) continue
            if (d.c.attrs["timeOnly"] != null) continue
            val (y, m, dd) = parseYmd(d.c.normalized) ?: continue
            d.c = d.c.copy(validation = DateValidator.validate(y, m, dd, letter, GIVEN_LETTER_PAST_YEARS))
        }
    }
}
