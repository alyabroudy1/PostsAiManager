package com.postsaimanager.core.domain.extraction.candidates

/**
 * Phone numbers (labelled, label alone in a cell, "unter ...", or by shape alone) and e-mail addresses.
 * A phone label only ends up as a hint; a number is a phone by its shape.
 */
internal object PhoneEmailFinder : CandidateFinder {

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    private val PHONE_LABELLED = Regex(
        "(?<![\\p{L}\\d])(Tel(?:efon)?\\.?|Fon|Fax|Telefax|Mobil(?:funk)?|Phone|Hotline|Servicetelefon|Servicenummer|Rufnummer|Handy)" +
            "\\s*[:.]?\\s*(\\+?\\d[\\d ()/\\-]{5,20}\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE_LABEL_ONLY = Regex(
        "(?<![\\p{L}\\d])(Tel(?:efon)?\\.?|Fon|Fax|Telefax|Mobil(?:funk)?|Phone|Hotline|Servicetelefon|Servicenummer|Rufnummer|Handy)\\s*[:.]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE_VALUE = Regex("^\\s*(\\+?\\d[\\d ()/\\-]{5,20}\\d)\\s*$")
    private val PHONE_SHAPE = Regex(
        "(?<![\\p{L}\\d+./-])(?:\\+\\d{1,3}(?:[ ./()-]?\\d){6,12}|0\\d{1,4}(?:[ /()-]\\d{2,}){2,})(?![\\p{L}\\d])",
    )
    private val PHONE_UNTER = Regex("(?<![\\p{L}\\d])unter\\s+(?:der\\s+Nummer\\s+)?(\\+?\\d[\\d ()/\\-]{6,20}\\d)", RegexOption.IGNORE_CASE)

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) {
            findPhones(ctx, line, out)
            findEmails(ctx, line, out)
        }
        return out
    }

    private fun findPhones(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)
        fun emit(range: IntRange, label: String, number: String) {
            if (!mask.free(range)) return
            val digits = number.count { it.isDigit() }
            mask.add(range)
            out += ctx.draft(
                line, range, CandidateKind.PHONE, number.trim(), collapseSpaces(number), label = label.trimEnd('.', ':', ' '),
                validation = if (digits in 6..15) Validation.Valid else Validation.Invalid("$digits digits"),
            )
        }
        for (m in PHONE_LABELLED.findAll(text)) emit(m.groups[2]!!.range, m.groupValues[1], m.groupValues[2])
        // label alone in its cell, number in the cell to the right ("Telefon:" | "0800 555 0199")
        PHONE_LABEL_ONLY.find(text)?.let { lm ->
            val source = ctx.rowNeighbour(line, left = false) ?: return@let
            val nm = PHONE_VALUE.find(source) ?: return@let
            if (!mask.free(lm.range)) return@let
            val number = nm.groupValues[1]
            mask.add(lm.range)
            out += ctx.draft(
                line, lm.range, CandidateKind.PHONE, number.trim(), collapseSpaces(number), evidence = "$text $source".trim(),
                label = lm.groupValues[1].trimEnd('.', ':', ' '),
                validation = if (number.count { it.isDigit() } in 6..15) Validation.Valid else Validation.Invalid("implausible phone number"),
            )
        }
        for (m in PHONE_UNTER.findAll(text)) emit(m.groups[1]!!.range, "unter", m.groupValues[1])
        // and by shape alone, whatever the words around it (or none): an international number with a plus,
        // or a number that starts with 0 and has an area code and at least two groups
        for (m in PHONE_SHAPE.findAll(text)) emit(m.range, "", m.value)
    }

    private fun findEmails(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val mask = ctx.mask(line)
        for (m in EMAIL.findAll(line.text)) {
            if (!mask.free(m.range)) continue
            mask.add(m.range)
            out += ctx.draft(line, m.range, CandidateKind.EMAIL, m.value, m.value.lowercase(), validation = Validation.Valid)
        }
    }
}
