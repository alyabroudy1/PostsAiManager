package com.postsaimanager.core.domain.extraction.candidates

/** Reference label rules; the first rule to claim a value span wins. */
private class RefRule(
    val subtype: ReferenceSubtype,
    label: String,
    value: String,
    val minLen: Int = 3,
    val extend: Boolean = false,
) {
    val full = Regex("(?<![\\p{L}\\d])($label)(?:\\s*:\\s*|\\s+)(?:(?:Nr|No)\\.?\\s*:?\\s*)?($value)", RegexOption.IGNORE_CASE)
    val labelOnly = Regex("(?<![\\p{L}\\d])($label)\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    val valueAtStart = Regex("^\\s*($value)", RegexOption.IGNORE_CASE)
}

private const val VAL_ID = "[A-Za-z0-9][A-Za-z0-9\\-/_.]{1,28}[A-Za-z0-9]"
private const val VAL_SPACED = "\\d{2,5}(?: \\d{2,5}){1,4}"

private val REF_RULES: List<RefRule> = listOf(
    RefRule(
        ReferenceSubtype.TAX_ID,
        "Steuer[-\\s]?ID(?:[-\\s]?Nr\\.?)?|Steuer-?[Ii]dentifikationsnummer|Identifikationsnummer|IdNr\\.?|Tax\\s*ID",
        "\\d{2}(?: ?\\d{3}){3}",
    ),
    RefRule(
        ReferenceSubtype.TAX_NO,
        "Steuer[-\\s]?(?:nummer|nr\\.?)|St\\.?[-\\s]?Nr\\.?|Tax\\s*(?:number|no\\.?|ref)",
        "\\d{2,3}(?:/\\d{2,5}){1,3}|\\d{10,13}",
    ),
    RefRule(ReferenceSubtype.BEITRAGSNUMMER, "Beitrags[-\\s]?(?:nummer|nr\\.?)", "\\d{3} ?\\d{3} ?\\d{3}"),
    RefRule(
        ReferenceSubtype.INVOICE_NO,
        "Rechnungs?[-\\s]?(?:nummer|nr\\.?|no\\.?)|Rechnung\\s+Nr\\.?|Invoice\\s*(?:number|no\\.?|#)|Belegnummer|Re\\.?[-\\s]?Nr\\.?",
        VAL_ID,
    ),
    RefRule(ReferenceSubtype.INVOICE_NO, "Rechnung(?=\\s+[A-Za-z0-9]*\\d)|(?-i:RE)(?=\\s+\\d)", VAL_ID, minLen = 6),
    RefRule(
        ReferenceSubtype.CUSTOMER_NO,
        "Kunden[-\\s]?(?:nummer|nr\\.?|no\\.?)|Kundennr\\.?|Kd\\.?[-\\s]?(?:Nr\\.?)?|Customer\\s*(?:number|no\\.?|ID|ref)",
        VAL_ID,
    ),
    RefRule(
        ReferenceSubtype.CONTRACT_NO,
        "Vertrags[-\\s]?(?:nummer|nr\\.?)|Vertragskonto(?:nummer)?|Contract\\s*(?:number|no\\.?)|" +
            "(?:Wartungs|Miet|Liefer|Kauf|Dienst)?vertrag(?=\\s+(?-i:[A-Z]{1,5})-?\\d)",
        "$VAL_SPACED|$VAL_ID",
    ),
    RefRule(
        ReferenceSubtype.POLICY_NO,
        "Versicherungs[-\\s]?(?:nummer|nr\\.?|schein[-\\s]?(?:nummer|nr\\.?))|Policen?[-\\s]?(?:nummer|nr\\.?)|" +
            "Police(?=\\s+[A-Za-z0-9-]*\\d)|Policy\\s*(?:number|no\\.?)|Vers\\.?[-\\s]?Nr\\.?",
        VAL_ID,
    ),
    RefRule(
        ReferenceSubtype.INSURANCE_NO,
        "Versichertennummer|Versicherten[-\\s]?Nr\\.?|Krankenversichertennummer|KVNR|Rentenversicherungsnummer|" +
            "Sozialversicherungsnummer|Mitgliedsnummer|Member\\s*(?:ID|number)",
        VAL_ID,
    ),
    RefRule(
        ReferenceSubtype.CASE_NO,
        "Aktenzeichen|Az\\.?|Geschäftszeichen|Gz\\.?|Case\\s*(?:number|no\\.?|ref(?:erence)?)|Vorgangsnummer|Bearbeitungsnummer|Geschäftsnummer",
        VAL_ID,
        extend = true,
    ),
    RefRule(
        ReferenceSubtype.ACCOUNT_NO,
        "(?:Your\\s+)?Account\\s*(?:number|no\\.?)|Konto[-\\s]?(?:nummer|nr\\.?)|(?:Your\\s+)?account(?=\\s+(?-i:[A-Z]{1,4})-?\\d)",
        VAL_ID,
    ),
    RefRule(ReferenceSubtype.METER_NO, "Zähler[-\\s]?(?:nummer|nr\\.?)|Meter\\s*(?:number|no\\.?)", "[A-Za-z0-9]{6,20}", minLen = 6),
    RefRule(ReferenceSubtype.MATRICULATION_NO, "Matrikel[-\\s]?(?:nummer|nr\\.?)", VAL_ID),
    RefRule(ReferenceSubtype.RECEIPT_NO, "Bon[-\\s]?(?:Nr\\.?|nummer)|Kassenbon[-\\s]?Nr\\.?|Receipt\\s*(?:number|no\\.?)", VAL_ID, minLen = 3),
    RefRule(
        ReferenceSubtype.OTHER,
        "Unser\\s+Zeichen|Ihr\\s+Zeichen|Ihre\\s+Zeichen|Unser\\s+Az\\.?|Your\\s+ref(?:erence)?|Our\\s+ref(?:erence)?|" +
            "Reference|Referenz(?:nummer)?|Ref\\.?|Mandatsreferenz",
        VAL_ID,
        extend = true,
    ),
)

/**
 * Labelled references (Kundennummer, Aktenzeichen, Steuer-ID ...): the label picks the subtype and the
 * value shape checks the value. A label at the end of a line takes its value from the cell to the right
 * or the line below. Tokens that only look like identifiers are [IdentifierFinder]'s.
 */
internal object ReferenceFinder : CandidateFinder {

    private val EXTEND_TOKEN = Regex("^ ([A-Za-z0-9/\\-]+)")

    override fun find(ctx: ExtractionContext): List<Draft> {
        val out = ArrayList<Draft>()
        for (line in ctx.activeLines) findIn(ctx, line, out)
        return out
    }

    private fun acceptRefValue(rule: RefRule, value: String): Boolean {
        val v = value.trim()
        if (v.length < rule.minLen || v.none { it.isDigit() }) return false
        if (DateFinder.NUM_DATE.matches(v) || DateFinder.ISO_DATE.matches(v)) return false
        if (v.length >= 4 && IbanValidator.lengths.containsKey(v.take(2).uppercase()) && v.filter { it.isLetterOrDigit() }.length in 15..34 &&
            v.substring(2, 4).all { it.isDigit() }
        ) return false
        return true
    }

    private fun extendValue(text: String, valueEnd: Int, first: String): Pair<String, Int> {
        var value = first
        var end = valueEnd
        while (true) {
            val tm = EXTEND_TOKEN.find(text.substring(end)) ?: break
            val t = tm.groupValues[1]
            if (t.none { it.isDigit() } || t.length > 12 || value.length + 1 + t.length > 30) break
            value += " $t"
            end += tm.value.length
        }
        return value to end
    }

    private fun draftOf(
        ctx: ExtractionContext,
        line: SourceLine,
        range: IntRange,
        rule: RefRule,
        label: String,
        value: String,
        evidence: String = line.text,
    ): Draft {
        val v = collapseSpaces(value)
        // OCR confusion (o/O for 0, I/l for 1) inside a long digit-heavy token: the repaired reading is the
        // normalised value, the raw text stays as printed and as the evidence.
        val repaired = IdentifierRepair.repair(v)
        return ctx.draft(
            line, range, CandidateKind.REFERENCE, v, repaired ?: v, evidence, label = collapseSpaces(label).trimEnd(':', ' '),
            subtype = rule.subtype, validation = ReferenceValidator.validate(rule.subtype, repaired ?: v),
            attrs = if (repaired != null) mapOf("repaired" to "o/O->0, I/l->1") else emptyMap(),
        )
    }

    private fun findIn(ctx: ExtractionContext, line: SourceLine, out: MutableList<Draft>) {
        val text = line.text
        val mask = ctx.mask(line)
        for (rule in REF_RULES) {
            for (m in rule.full.findAll(text)) {
                val valueGroup = m.groups[m.groups.size - 1] ?: continue
                var value = valueGroup.value
                var vEnd = valueGroup.range.last + 1
                if (rule.extend) {
                    val (v2, e2) = extendValue(text, vEnd, value)
                    value = v2
                    vEnd = e2
                }
                val vRange = valueGroup.range.first until vEnd
                if (!mask.free(vRange) || !acceptRefValue(rule, value)) continue
                mask.add(vRange)
                out += draftOf(ctx, line, vRange, rule, m.groupValues[1], value)
            }
            // label at the end of the line, value in the cell to the right or the line below
            val lo = rule.labelOnly.find(text)
            if (lo != null) {
                val cont = ctx.continuation(line)?.text
                val source = ctx.rowNeighbour(line, left = false) ?: cont ?: continue
                val vm = rule.valueAtStart.find(source) ?: continue
                var value = vm.groupValues[1]
                if (rule.extend) value = extendValue(source, vm.groups[1]!!.range.last + 1, value).first
                if (!acceptRefValue(rule, value)) continue
                val r = lo.range
                if (!mask.free(r)) continue
                mask.add(r)
                out += draftOf(ctx, line, r, rule, lo.groupValues[1], value, evidence = "$text $source".trim())
            }
        }
    }
}
