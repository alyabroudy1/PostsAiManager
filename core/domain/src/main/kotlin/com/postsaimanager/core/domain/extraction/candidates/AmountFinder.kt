package com.postsaimanager.core.domain.extraction.candidates

/** A currency by shape, shared by the finders that must tell money from a plain figure. */
internal object CurrencyShape {
    /**
     * A currency by shape: any currency sign (Unicode category Sc) or an upper-case ISO 4217 code taken
     * from the platform's currency data. Currency names are only a hint (see [CurrencyNames]).
     */
    val CURRENCY: String = "\\p{Sc}|(?-i:" + AmountParser.isoCodes.sorted().joinToString("|") + ")"

    /** A currency sign or code standing on its own in a cell or line (a table header such as "Betrag in EUR"). */
    val CURRENCY_TOKEN = Regex("(?<![\\p{L}\\p{Nd}])($CURRENCY)(?![\\p{L}\\p{Nd}])")
}

/**
 * Numbers with a decimal or a currency: AMOUNT or NUMBER candidates.
 *
 * A number is money by evidence, never by a word next to it. AMOUNT: a currency sign or ISO code
 * sits right before or after it, it takes part in an arithmetic triple, or it stands in a table
 * column that has a currency (see [AmountEvidence]). Any other number with cents is a NUMBER: a
 * percentage (`%` after it), a rate (`/` and a letter after it) or a plain figure such as a
 * quantity. What a unit word says ("kWh", "Tage") is not looked at.
 */
internal object AmountFinder : CandidateFinder {

    /** A number with an optional sign, an optional currency sign or code before or after it. Whether it is money is decided in [find]. */
    private val AMOUNT = Regex(
        "(?<![\\p{L}\\d.,])((?<=^|[\\s(:])[-\\u2212])?(?:(${CurrencyShape.CURRENCY})\\s?)?" +
            "(\\d{1,3}(?:[.,\\u2019']\\d{3})+(?:[.,]\\d{2})?|\\d+(?:[.,]\\d{2})?)(,-{1,2})?(?!\\d)" +
            "(?:\\s?(${CurrencyShape.CURRENCY})(?![\\p{L}]))?",
        RegexOption.IGNORE_CASE,
    )

    /** A percentage sign right after a number: a shape, not a word. */
    private val PERCENT_AFTER = Regex("^\\s?[%‰]")

    /** A slash and a letter right after a number ("1,79 EUR/kg", "0,32 €/kWh"): a rate, marked by the slash. */
    private val RATE_AFTER = Regex("^\\s?/\\s?\\p{L}")

    /** A decimal or grouped number that is a value with cents, as opposed to a plain integer. */
    private val CENTS_SHAPE = Regex("[.,]\\d{2}$")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)
        for (m in AMOUNT.findAll(text)) {
            if (!mask.free(m.range)) continue
            val sign = m.groupValues[1]
            val c1 = m.groupValues[2]
            val num = m.groupValues[3]
            val dash = m.groupValues[4]
            val c2 = m.groupValues[5]
            var currencyToken = c1.ifEmpty { c2 }
            var range = m.range
            val after = text.substring(m.range.last + 1)
            val percent = PERCENT_AFTER.containsMatchIn(after)
            val rate = RATE_AFTER.containsMatchIn(after)
            var attrsExtra = emptyMap<String, String>()
            if (currencyToken.isEmpty() && !percent && !rate) {
                // A currency name from the platform's locale data right behind the number: a hint that counts as a currency.
                CurrencyNames.codeAfter(after)?.let { (code, length) ->
                    val longer = m.range.first..(m.range.last + length)
                    if (mask.free(longer)) {
                        currencyToken = code
                        range = longer
                        attrsExtra = mapOf("currencyName" to "true")
                    }
                }
            }
            if (currencyToken.isEmpty() && (dash.isNotEmpty() || !CENTS_SHAPE.containsMatchIn(num))) continue
            val money = AmountParser.parse(num, currencyToken.ifEmpty { null }, sign.isNotEmpty()) ?: continue
            val attrs = mapOf(
                "cents" to money.cents.toString(),
                "currency" to money.currency,
                "currencyExplicit" to money.currencyExplicit.toString(),
                "numberText" to num,
            ) + attrsExtra
            mask.add(range)
            val raw = text.substring(range.first, range.last + 1).trim()
            if (currencyToken.isNotEmpty() && !percent && !rate) {
                out += ctx.draft(
                    line, range, CandidateKind.AMOUNT, raw, money.canonical(),
                    validation = if (money.currencyExplicit) Validation.Valid else Validation.Unchecked,
                    attrs = attrs,
                )
            } else {
                val shape = mapOf("percent" to percent, "rate" to (rate && !percent)).filterValues { it }.mapValues { "true" }
                out += ctx.draft(line, range, CandidateKind.NUMBER, raw, plainNumber(money.cents), attrs = attrs + shape)
            }
        }
    }

    private fun plainNumber(cents: Long): String {
        val abs = kotlin.math.abs(cents)
        return "${if (cents < 0) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
