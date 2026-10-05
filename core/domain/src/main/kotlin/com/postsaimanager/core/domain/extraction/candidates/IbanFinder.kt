package com.postsaimanager.core.domain.extraction.candidates

/**
 * IBANs: two capitals and two check characters, then runs of letters and digits, possibly split by
 * spaces or over two lines. A check character or digit OCR read as a letter (o/O for 0, I/l for 1) is
 * repaired only when the mod-97 checksum then holds. A label ("IBAN", "Konto") is only a hint.
 */
internal object IbanFinder : CandidateFinder {

    /** Two capitals and two check characters; a check character OCR read as a letter (o/O for 0, I/l for 1) is allowed and repaired later. */
    private val IBAN_START = Regex("(?<![A-Za-z0-9])[A-Z]{2}(?:\\d{2}|[0-9oOIl]{2})(?=[ A-Za-z0-9]|$)")
    private val IBAN_LABEL = Regex(
        "(?<![\\p{L}])(?:IBAN|Kontoinhaber(?:in)?|Einzugskonto|Bankverbindung|Konto|Empfänger|account)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    /** The run of IBAN characters (upper-case letters, digits, and the o / l that OCR confuses with 0 / 1) at [p]. */
    private fun runAt(s: String, p: Int): String {
        var q = p
        while (q < s.length && (s[q] in 'A'..'Z' || s[q] in '0'..'9' || s[q] == 'o' || s[q] == 'l')) q++
        return s.substring(p, q)
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)
        for (m in IBAN_START.findAll(text)) {
            val cc = m.value.substring(0, 2)
            val expected = IbanValidator.lengths[cc] ?: continue
            val check = m.value.substring(2).map { if (it in "oO") '0' else if (it in "Il") '1' else it }.joinToString("").toInt()
            if (check !in 2..98) continue
            if (!mask.free(m.range)) continue
            // read runs of [A-Z0-9] (and the o / l OCR confuses with 0 / 1), separated by single spaces
            val sb = StringBuilder()
            var pos = m.range.first
            var end = pos
            val first = runAt(text, pos)
            if (pos + first.length < text.length && text[pos + first.length].let { it.isLetter() || it == '-' }) continue
            sb.append(first)
            pos += first.length
            end = pos
            while (sb.length < expected && pos < text.length && text[pos] == ' ') {
                val run = runAt(text, pos + 1)
                if (run.isEmpty() || run.length > 4) break
                if (pos + 1 + run.length < text.length && text[pos + 1 + run.length].let { it.isLetter() }) break
                sb.append(run)
                pos += 1 + run.length
                end = pos
            }
            var raw = text.substring(m.range.first, end)
            var evidence = text
            if (sb.length < expected && text.substring(end).isBlank()) {
                val cont = ctx.continuation(line)
                if (cont != null) {
                    var p2 = 0
                    val ct = cont.text
                    val joined = StringBuilder()
                    while (sb.length < expected && p2 < ct.length) {
                        if (p2 > 0) {
                            if (ct[p2] != ' ') break
                            p2++
                        }
                        val run = runAt(ct, p2)
                        if (run.isEmpty() || run.length > 4) break
                        sb.append(run)
                        joined.append(' ').append(run)
                        p2 += run.length
                    }
                    if (joined.isNotEmpty()) {
                        raw += joined.toString()
                        evidence = "$text ${ct.substring(0, p2)}".trim()
                    }
                }
            }
            val compact = sb.toString()
            if (compact.length < expected - 3 || compact.length > expected + 3) continue
            val pre = text.substring(0, m.range.first).takeLast(40)
            val label = IBAN_LABEL.findAll(pre).lastOrNull()?.value.orEmpty()
            mask.add(m.range.first until end)
            var normalized = compact.uppercase()
            var validation = IbanValidator.validate(compact)
            val attrs = mutableMapOf("country" to cc)
            // OCR read a digit as a letter (o/O for 0, I/l for 1): accept the repaired reading only when
            // its mod-97 checksum then holds, and say so; the raw text stays as printed.
            if (validation.isInvalid || compact.any { it in "ol" }) {
                IbanValidator.repair(compact)?.let {
                    normalized = it
                    validation = Validation.Valid
                    attrs["repaired"] = "o/O->0, I/l->1"
                }
            }
            out += ctx.draft(line, m.range.first until end, CandidateKind.IBAN, raw, normalized, evidence, label, validation = validation, attrs = attrs)
        }
    }
}
