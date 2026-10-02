package com.postsaimanager.core.domain.extraction.candidates

/** Countries a BIC may name: the IBAN countries plus the other large banking countries. */
private val BIC_COUNTRIES: Set<String> =
    IbanValidator.lengths.keys + setOf("US", "CA", "JP", "CN", "AU", "NZ", "IN", "HK", "SG", "ZA", "MX", "AR", "RU", "KR")

/**
 * BICs written with a label ("BIC", "SWIFT"). The label is only a hint that makes the token certain; a
 * token of the BIC shape without a label is set aside on the context for [ShapeBicFinder].
 */
internal object BicFinder : CandidateFinder {

    private val BIC = Regex(
        "(?<![\\p{L}\\d])(BIC(?:/SWIFT)?|SWIFT(?:[-\\s]?Code)?)\\s*[:.]?\\s*([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    /** The BIC shape alone: 8 or 11 upper-case characters, no label. Accepted only next to an IBAN of the same country. */
    private val BIC_SHAPE = Regex("(?<![\\p{L}\\p{Nd}])[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?(?![\\p{L}\\p{Nd}])")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) {
            val mask = ctx.mask(line)
            for (m in BIC.findAll(line.text)) {
                if (!mask.free(m.range)) continue
                val code = m.groupValues[2].uppercase()
                val valid = if (code.substring(4, 6) in BIC_COUNTRIES) Validation.Valid else Validation.Invalid("unknown country ${code.substring(4, 6)}")
                mask.add(m.range)
                out += ctx.draft(line, m.range, CandidateKind.BIC, m.groupValues[2], code, label = m.groupValues[1].uppercase(), validation = valid)
            }
            for (m in BIC_SHAPE.findAll(line.text)) {
                if (mask.free(m.range)) ctx.bicShapes += line to m
            }
        }
        return out
    }
}

/**
 * A token of the BIC shape (4 letters, 2 country letters, 2 characters, optional 3) whose country
 * is the country of an IBAN found on the same page is a BIC, with no label needed: a bank code
 * and the account it belongs to agree on the country. The words before it stay as a hint.
 *
 * It runs after every other finder, because it needs the IBANs and yields to whatever already owns the characters.
 */
internal object ShapeBicFinder : CandidateFinder {

    private val WHITE = Regex("\\s+")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        val countries = ctx.drafts.filter { it.c.kind == CandidateKind.IBAN }
            .groupBy({ it.c.page }, { it.c.attrs["country"].orEmpty() })
        for ((line, m) in ctx.bicShapes) {
            val code = m.value
            if (code.substring(4, 6) !in countries[line.page].orEmpty()) continue
            // A name-shaped line is a weaker claim on the same characters; anything else already owns them.
            if (ctx.drafts.any { it.line === line && it.c.kind != CandidateKind.NAME && it.start <= m.range.last && m.range.first < it.end }) continue
            val before = line.text.substring(0, m.range.first).trim().trimEnd(':', '.', ' ')
            val hint = before.split(WHITE).filter { it.isNotEmpty() }.takeLast(2).joinToString(" ")
            out += ctx.draft(line, m.range, CandidateKind.BIC, code, code, label = hint, validation = Validation.Valid, attrs = mapOf("shape" to "true"))
        }
        return out
    }
}
