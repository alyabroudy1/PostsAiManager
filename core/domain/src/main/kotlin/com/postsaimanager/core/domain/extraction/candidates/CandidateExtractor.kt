package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.OcrBlock
import java.time.LocalDate

/**
 * Deterministic candidate finder.
 *
 * Reads OCR blocks (page aware) and proposes every date, amount, IBAN, BIC, reference, phone,
 * e-mail and (when the layout stage tells us where the address field is) name it can see, each
 * with its page, bbox, evidence line, nearest label and a validation verdict. It never decides
 * which candidate fills which slot.
 *
 * Pure Kotlin: no Android, no I/O. Safe to run in JVM tests and in a worker.
 */
object CandidateExtractor {

    /**
     * @param pages OCR blocks per page (page 1 first).
     * @param zones optional layout hints per block (workstream B); names are only proposed from
     *   blocks tagged [BlockZone.ADDRESS_FIELD], [BlockZone.LETTERHEAD] or
     *   [BlockZone.RETURN_ADDRESS], plus explicit routing lines (z. Hd., c/o, Erziehungsberechtigte
     *   von) wherever they appear.
     * @param letterDate the letter's date when already known; otherwise it is inferred from a
     *   labelled date ("Datum:", "Rechnungsdatum", "Ort, 26 September 2026") to range-check the others.
     */
    fun extract(
        pages: List<List<OcrBlock>>,
        zones: Map<BlockKey, BlockZone> = emptyMap(),
        letterDate: LocalDate? = null,
    ): CandidateSet = Run(pages, zones, letterDate).execute()

    /** Plain-text entry point (no bounds, one page); used by callers that only have text. */
    fun extractFromText(text: String, letterDate: LocalDate? = null): CandidateSet =
        Run(emptyList(), emptyMap(), letterDate, text).execute()
}

// ─────────────────────────────────────────────────────────────────────────────

private class SourceLine(
    val page: Int,
    val blockIndex: Int,
    val order: Int,
    val text: String,
    val block: OcrBlock?,
    val zone: BlockZone?,
    val nextInBlock: Boolean, // there is another line after this one in the same block
)

private class Draft(var c: Candidate, val line: SourceLine, val start: Int, val end: Int)

private object P {
    const val EUR = "EUR|Euro|\u20AC|\u00A3|GBP|USD|US\\u0024|\\u0024|CHF"

    val MONTHS: Map<String, Int> = mapOf(
        "januar" to 1, "january" to 1, "jan" to 1, "februar" to 2, "february" to 2, "feb" to 2,
        "m\u00E4rz" to 3, "maerz" to 3, "march" to 3, "m\u00E4r" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "mai" to 5, "may" to 5, "juni" to 6, "june" to 6, "jun" to 6, "juli" to 7, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sept" to 9, "sep" to 9, "oktober" to 10, "october" to 10,
        "okt" to 10, "oct" to 10, "november" to 11, "nov" to 11, "dezember" to 12, "december" to 12, "dez" to 12, "dec" to 12,
    )
    private val monthAlt = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    val ISO_DATE = Regex("(?<![\\d-])(\\d{4})-(\\d{2})-(\\d{2})(?!\\d|-\\d)")
    val NUM_DATE = Regex("(?<![\\d.,/])(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d|[.,]\\d)")
    val LONG_DATE = Regex(
        "(?<![\\d.,])(\\d{1,2})(?:st|nd|rd|th)?\\.?\\s*($monthAlt)\\.?,?\\s*(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    val MDY_DATE = Regex(
        "(?<![\\p{L}\\d])($monthAlt)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    val TIME_AFTER = Regex(
        "^(?:\\s*,)?\\s+(?:(um|at|@|ab|gegen)\\s+)?(\\d{1,2})([:.])(\\d{2})(?::\\d{2})?(?:\\s*(Uhr|h)(?![\\p{L}]))?",
        RegexOption.IGNORE_CASE,
    )
    val TIME_ONLY = Regex("(?<![\\d:.])(\\d{1,2})[:.](\\d{2})\\s*Uhr(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private const val NUMBER_WORDS =
        "einem|einer|eines|einen|ein|zwei|drei|vier|f\u00FCnf|sechs|sieben|acht|neun|zehn|zw\u00F6lf|" +
            "one|two|three|four|five|six|seven|eight|nine|ten|twelve"
    val RELATIVE = Regex(
        "(?:innerhalb\\s+(?:von\\s+|eines\\s+|einer\\s+|der\\s+)?|binnen\\s+(?:eines\\s+|einer\\s+)?|" +
            "within\\s+(?:a\\s+|an\\s+)?|nach\\s+Ablauf\\s+von\\s+|sp\u00E4testens\\s+nach\\s+)" +
            "(\\d+|$NUMBER_WORDS)\\s+" +
            "(Werktag(?:es|e|en)?|Arbeitstag(?:es|e|en)?|Tag(?:es|e|en)?|Woche[n]?|Monat(?:s|e|en)?|Jahr(?:es|e|en)?|" +
            "working\\s+days?|business\\s+days?|days?|weeks?|months?|years?)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )
    val ANCHOR = Regex("^\\s+(?:nach|ab|seit|following|after|from|of)\\s+(.{2,80})", RegexOption.IGNORE_CASE)
    val ANCHOR_STOP = Regex(
        "\\s+(?:auf|bei|beim|an|per|schriftlich|und|oder|bitte|unter|gem\u00E4\u00DF|zu|to|at|by|and|or|mit|im|in)\\b|[.,;:]",
        RegexOption.IGNORE_CASE,
    )
    val NUMBER_WORD_VALUES = mapOf(
        "ein" to 1, "einem" to 1, "einer" to 1, "eines" to 1, "einen" to 1, "one" to 1, "zwei" to 2, "two" to 2,
        "drei" to 3, "three" to 3, "vier" to 4, "four" to 4, "f\u00FCnf" to 5, "five" to 5, "sechs" to 6, "six" to 6,
        "sieben" to 7, "seven" to 7, "acht" to 8, "eight" to 8, "neun" to 9, "nine" to 9, "zehn" to 10, "ten" to 10,
        "zw\u00F6lf" to 12, "twelve" to 12,
    )

    val AMOUNT = Regex(
        "(?<![\\p{L}\\d.,])((?<=^|[\\s(:])[-\\u2212])?(?:($EUR)\\s?)?" +
            "(\\d{1,3}(?:[.,\\u2019']\\d{3})+(?:[.,]\\d{2})?|\\d+(?:[.,]\\d{2})?)(,-{1,2})?(?!\\d)" +
            "(?:\\s?($EUR|\u064A\u0648\u0631\u0648)(?![\\p{L}]))?",
        RegexOption.IGNORE_CASE,
    )
    val AMOUNT_NOT_MONEY_AFTER = Regex(
        "^\\s?(?:%|Prozent|kWh|kg|m\u00B2|m2|qm|Liter|km|Std\\b|St\u00FCck|Tage|Tagen|Jahre|Monate)",
        RegexOption.IGNORE_CASE,
    )
    val PER_UNIT_AFTER = Regex("^\\s?/\\s?(?:kg|kwh|m|l|st|std|h|tag|monat|jahr|month|year|day)\\b", RegexOption.IGNORE_CASE)

    val IBAN_START = Regex("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?=[ A-Z0-9]|$)")
    val IBAN_LABEL = Regex(
        "(?<![\\p{L}])(?:IBAN|Kontoinhaber(?:in)?|Einzugskonto|Bankverbindung|Konto|Empf\u00E4nger|account)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )
    val BIC = Regex(
        "(?<![\\p{L}\\d])(BIC(?:/SWIFT)?|SWIFT(?:[-\\s]?Code)?)\\s*[:.]?\\s*([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    val PHONE_LABELLED = Regex(
        "(?<![\\p{L}\\d])(Tel(?:efon)?\\.?|Fon|Fax|Telefax|Mobil(?:funk)?|Phone|Hotline|Servicetelefon|Servicenummer|Rufnummer|Handy)" +
            "\\s*[:.]?\\s*(\\+?\\d[\\d ()/\\-]{5,20}\\d)",
        RegexOption.IGNORE_CASE,
    )
    val PHONE_LABEL_ONLY = Regex(
        "(?<![\\p{L}\\d])(Tel(?:efon)?\\.?|Fon|Fax|Telefax|Mobil(?:funk)?|Phone|Hotline|Servicetelefon|Servicenummer|Rufnummer|Handy)\\s*[:.]?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    val PHONE_VALUE = Regex("^\\s*(\\+?\\d[\\d ()/\\-]{5,20}\\d)\\s*$")
    val PHONE_UNTER = Regex("(?<![\\p{L}\\d])unter\\s+(?:der\\s+Nummer\\s+)?(\\+?\\d[\\d ()/\\-]{6,20}\\d)", RegexOption.IGNORE_CASE)

    // ── names ──
    val SALUTATION = Regex(
        "^(?:Herrn\\s+und\\s+Frau|Herr\\s+und\\s+Frau|Herrn|Herr|Frau|Fr\\.|Hr\\.|Fr\u00E4ulein|Eheleute|Mr\\.?|Mrs\\.?|Ms\\.?|Miss|Mx\\.?)(?=\\s|$)\\s*",
        RegexOption.IGNORE_CASE,
    )
    val ROUTE_ZHD = Regex("^(z\\.?\\s?Hd\\.?|z\\.?\\s?H\\.|zu\\s+H\u00E4nden(?:\\s+von)?|zH|Attn\\.?:?|Attention:?)\\s+(.+)$", RegexOption.IGNORE_CASE)
    val ROUTE_CO = Regex("^(c/o|c\\.o\\.)\\s+(.+)$", RegexOption.IGNORE_CASE)
    val ROUTE_GUARDIAN = Regex(
        "^((?:Erziehungsberechtigte[rn]?|Eltern|Vormund|Betreuer(?:in)?|Sorgeberechtigte[rn]?)\\s+(?:von|des|der))\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    val ORG_WORDS = Regex(
        "(?<![\\p{L}])(?:GmbH|mbH|AG|KG|OHG|GbR|UG|e\\.\\s?V\\.|eG|SE|Ltd\\.?|Inc\\.?|LLC|Co\\.|Corp\\.?|Stiftung|Verband|Verein|" +
            "Universit\u00E4t|Hochschule|Grundschule|Schule|Finanzamt|Stadtwerke|Versicherung(?:en)?|Bank|Sparkasse|Amt|Amtsgericht|" +
            "Praxis|Kanzlei|Hausverwaltung|Verwaltung|Beitragsservice|Jobcenter|Gemeinde|Stadt|Kreis|Ministerium|Kammer|" +
            "Krankenkasse|Kasse|Klinikum|Krankenhaus|Beh\u00F6rde|Utilities|Services|Partner|Consulting|Mobilfunk|Institut|Agentur|Gesellschaft|Company|Firma)" +
            "(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )
    val STREET = Regex(
        "(?i)^(?:.*(?:stra\u00DFe|strasse|str\\.|weg|platz|allee|gasse|ring|damm|ufer|street|road|lane|avenue)\\s*\\d+\\s*[a-z]?|.*\\s\\d+\\s?[a-z]?)$",
    )
    val POSTAL = Regex("^(?:[A-Z]{1,2}[- ])?\\d{4,5}\\s+\\p{L}")
    val WEEKDAY = Regex(
        "(?i)^(?:Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonnabend|Sonntag|Mo|Di|Mi|Do|Fr|Sa|So|" +
            "Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\.?,?$",
    )
    val PLACE_COMMA = Regex("^\\p{L}[\\p{L}.\\- ]{1,40},\\s*$")
    val CONNECTORS = setOf("und", "&", "+", "von", "van", "de", "der", "zu", "zur", "vom", "el", "al", "bin", "ibn", "and")
}

/** Reference label rules; the first rule to claim a value span wins. */
private class RefRule(
    val subtype: ReferenceSubtype,
    label: String,
    value: String,
    val minLen: Int = 3,
    val extend: Boolean = false,
) {
    val labelRegex = Regex("(?<![\\p{L}\\d])($label)", RegexOption.IGNORE_CASE)
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
        "Aktenzeichen|Az\\.?|Gesch\u00E4ftszeichen|Gz\\.?|Case\\s*(?:number|no\\.?|ref(?:erence)?)|Vorgangsnummer|Bearbeitungsnummer|Gesch\u00E4ftsnummer",
        VAL_ID,
        extend = true,
    ),
    RefRule(
        ReferenceSubtype.ACCOUNT_NO,
        "(?:Your\\s+)?Account\\s*(?:number|no\\.?)|Konto[-\\s]?(?:nummer|nr\\.?)|(?:Your\\s+)?account(?=\\s+(?-i:[A-Z]{1,4})-?\\d)",
        VAL_ID,
    ),
    RefRule(ReferenceSubtype.METER_NO, "Z\u00E4hler[-\\s]?(?:nummer|nr\\.?)|Meter\\s*(?:number|no\\.?)", "[A-Za-z0-9]{6,20}", minLen = 6),
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

// ─────────────────────────────────────────────────────────────────────────────

private class Run(
    private val pages: List<List<OcrBlock>>,
    private val zones: Map<BlockKey, BlockZone>,
    private val givenLetterDate: LocalDate?,
    private val plainText: String? = null,
) {
    private val lines = ArrayList<SourceLine>()
    private val drafts = ArrayList<Draft>()

    fun execute(): CandidateSet {
        buildLines()
        for ((i, line) in lines.withIndex()) {
            if (NoiseFilter.isNoiseLine(line.text)) continue
            processLine(line, lines.getOrNull(i + 1))
        }
        drafts.sortWith(compareBy({ it.line.order }, { it.start }))
        labelDrafts()
        val letter = givenLetterDate ?: inferLetterDate()
        validateDates(letter)
        markTriples()
        return CandidateSet(assignIds(), letter)
    }

    // ── input ────────────────────────────────────────────────────────────────

    private fun normalizeChars(s: String): String = OcrText.normalizeChars(s)

    private fun buildLines() {
        var order = 0
        if (plainText != null) {
            val texts = plainText.split('\n').map { normalizeChars(it).trim() }.filter { it.isNotEmpty() }
            texts.forEachIndexed { i, t -> lines.add(SourceLine(1, i, order++, t, null, null, false)) }
            return
        }
        for ((pi, blocks) in pages.withIndex()) {
            for ((bi, block) in blocks.withIndex()) {
                val zone = zones[BlockKey(pi + 1, bi)]
                val texts = normalizeChars(block.text).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                texts.forEachIndexed { li, t ->
                    lines.add(SourceLine(pi + 1, bi, order++, t, block, zone, li < texts.size - 1))
                }
            }
        }
    }

    // ── neighbours ───────────────────────────────────────────────────────────

    private fun blockOf(line: SourceLine): OcrBlock? = line.block

    private fun rowNeighbour(line: SourceLine, left: Boolean): String? {
        val b = blockOf(line) ?: return null
        val blocks = pages.getOrNull(line.page - 1) ?: return null
        var best: OcrBlock? = null
        for ((i, o) in blocks.withIndex()) {
            if (i == line.blockIndex) continue
            val ob = o.bounds
            val overlap = minOf(b.bounds.bottom, ob.bottom) - maxOf(b.bounds.top, ob.top)
            val minH = minOf(b.bounds.height, ob.height)
            if (minH <= 0f || overlap < 0.5f * minH) continue
            if (left) {
                if (ob.right > b.bounds.left + 0.01f || b.bounds.left - ob.right > 0.6f) continue
                if (best == null || ob.right > best.bounds.right) best = o
            } else {
                if (ob.left < b.bounds.right - 0.01f || ob.left - b.bounds.right > 0.6f) continue
                if (best == null || ob.left < best.bounds.left) best = o
            }
        }
        return best?.let { normalizeChars(it.text).replace('\n', ' ').trim() }?.takeIf { it.isNotEmpty() && !NoiseFilter.isNoiseLine(it) }
    }

    /** The line that follows [line] when it is really its continuation (same block, or right below). */
    private fun continuation(line: SourceLine, next: SourceLine?): SourceLine? {
        if (next == null || next.page != line.page) return null
        if (line.nextInBlock && next.blockIndex == line.blockIndex) return next
        val a = line.block?.bounds
        val b = next.block?.bounds
        if (a == null || b == null) return if (plainText != null) next else null
        return if (b.top - a.bottom in -0.01f..0.04f && kotlin.math.abs(b.left - a.left) < 0.15f) next else null
    }

    // ── per-line extraction ──────────────────────────────────────────────────

    private class Mask {
        private val r = ArrayList<IntRange>()
        fun free(range: IntRange) = r.none { it.first <= range.last && range.first <= it.last }
        fun add(range: IntRange) { r.add(range) }
    }

    private fun add(
        line: SourceLine,
        range: IntRange,
        kind: CandidateKind,
        raw: String,
        normalized: String,
        evidence: String = line.text,
        label: String = "",
        labelKind: LabelKind? = null,
        subtype: ReferenceSubtype? = null,
        validation: Validation = Validation.Unchecked,
        attrs: Map<String, String> = emptyMap(),
    ): Draft {
        val c = Candidate(
            id = "",
            kind = kind,
            raw = raw,
            normalized = normalized,
            page = line.page,
            bbox = line.block?.bounds,
            evidence = evidence,
            label = label,
            labelKind = labelKind,
            subtype = subtype,
            validation = validation,
            attrs = attrs,
        )
        return Draft(c, line, range.first, range.last + 1).also { drafts.add(it) }
    }

    private fun processLine(line: SourceLine, next: SourceLine?) {
        val mask = Mask()
        findIbans(line, next, mask)
        findDates(line, mask)
        findRelativeDeadlines(line, mask)
        findBics(line, mask)
        findReferences(line, next, mask)
        findAmounts(line, mask)
        findPhones(line, mask)
        findEmails(line, mask)
        findNames(line)
    }

    // IBAN ---------------------------------------------------------------------

    private fun findIbans(line: SourceLine, next: SourceLine?, mask: Mask) {
        val text = line.text
        for (m in P.IBAN_START.findAll(text)) {
            val cc = m.value.substring(0, 2)
            val expected = IbanValidator.lengths[cc] ?: continue
            val check = m.value.substring(2).toInt()
            if (check !in 2..98) continue
            if (!mask.free(m.range)) continue
            // read runs of [A-Z0-9], separated by single spaces
            val sb = StringBuilder()
            var pos = m.range.first
            var end = pos
            fun runAt(s: String, p: Int): String {
                var q = p
                while (q < s.length && (s[q] in 'A'..'Z' || s[q] in '0'..'9')) q++
                return s.substring(p, q)
            }
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
                val cont = continuation(line, next)
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
            val label = P.IBAN_LABEL.findAll(pre).lastOrNull()?.value.orEmpty()
            mask.add(m.range.first until end)
            add(
                line, m.range.first until end, CandidateKind.IBAN, raw, compact, evidence, label,
                validation = IbanValidator.validate(compact), attrs = mapOf("country" to cc),
            )
        }
    }

    // Dates ----------------------------------------------------------------------

    private fun findDates(line: SourceLine, mask: Mask) {
        val text = line.text
        fun emit(range: IntRange, y: Int, mo: Int, d: Int) {
            if (!mask.free(range)) return
            var end = range.last + 1
            var normalized = "%04d-%02d-%02d".format(y, mo, d)
            var kind = CandidateKind.DATE
            val tm = P.TIME_AFTER.find(text.substring(end))
            if (tm != null) {
                val hh = tm.groupValues[2].toInt()
                val mm = tm.groupValues[4].toInt()
                val sepDot = tm.groupValues[3] == "."
                val hasWord = tm.groupValues[1].isNotEmpty() || tm.groupValues[5].isNotEmpty()
                if (hh < 24 && mm < 60 && (!sepDot || hasWord)) {
                    end += tm.value.length
                    normalized += "T%02d:%02d".format(hh, mm)
                    kind = CandidateKind.DATETIME
                }
            }
            val r = range.first until end
            mask.add(r)
            add(line, r, kind, text.substring(r.first, end).trim(), normalized)
        }
        for (m in P.ISO_DATE.findAll(text)) emit(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        for (m in P.NUM_DATE.findAll(text)) {
            val yy = m.groupValues[3]
            val y = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
            emit(m.range, y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        for (m in P.LONG_DATE.findAll(text)) {
            val mo = P.MONTHS[m.groupValues[2].lowercase()] ?: continue
            emit(m.range, m.groupValues[3].toInt(), mo, m.groupValues[1].toInt())
        }
        for (m in P.MDY_DATE.findAll(text)) {
            val mo = P.MONTHS[m.groupValues[1].lowercase()] ?: continue
            emit(m.range, m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())
        }
        for (m in P.TIME_ONLY.findAll(text)) {
            val hh = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            if (hh >= 24 || mm >= 60 || !mask.free(m.range)) continue
            mask.add(m.range)
            add(
                line, m.range, CandidateKind.DATETIME, m.value, "T%02d:%02d".format(hh, mm),
                validation = Validation.Valid, attrs = mapOf("timeOnly" to "true"),
            )
        }
    }

    // Relative deadlines -----------------------------------------------------------

    private fun findRelativeDeadlines(line: SourceLine, mask: Mask) {
        val text = line.text
        for (m in P.RELATIVE.findAll(text)) {
            if (!mask.free(m.range)) continue
            val nWord = m.groupValues[1].lowercase()
            val n = nWord.toIntOrNull() ?: P.NUMBER_WORD_VALUES[nWord] ?: continue
            val unitWord = m.groupValues[2].lowercase()
            val (unit, letter) = when {
                unitWord.startsWith("werktag") || unitWord.startsWith("arbeitstag") ||
                    unitWord.startsWith("working") || unitWord.startsWith("business") -> "DAY" to 'D'
                unitWord.startsWith("tag") || unitWord.startsWith("day") -> "DAY" to 'D'
                unitWord.startsWith("woche") || unitWord.startsWith("week") -> "WEEK" to 'W'
                unitWord.startsWith("monat") || unitWord.startsWith("month") -> "MONTH" to 'M'
                else -> "YEAR" to 'Y'
            }
            var endIdx = m.range.last + 1
            var anchor = ""
            P.ANCHOR.find(text.substring(endIdx))?.let { am ->
                var a = am.groupValues[1]
                P.ANCHOR_STOP.find(a)?.let { stop -> a = a.substring(0, stop.range.first) }
                a = a.trim().take(60).trim()
                if (a.isNotEmpty()) {
                    anchor = a
                    endIdx += am.value.indexOf(a) + a.length
                }
            }
            val r = m.range.first until endIdx
            mask.add(r)
            val working = unitWord.startsWith("werktag") || unitWord.startsWith("arbeitstag") ||
                unitWord.startsWith("working") || unitWord.startsWith("business")
            add(
                line, r, CandidateKind.RELATIVE_DEADLINE, text.substring(r.first, endIdx).trim(), "P$n$letter",
                validation = if (n in 1..730) Validation.Valid else Validation.Invalid("implausible period $n"),
                attrs = buildMap {
                    put("n", n.toString())
                    put("unit", unit)
                    put("anchor", anchor)
                    if (working) put("workingDays", "true")
                },
            )
        }
    }

    // BIC -------------------------------------------------------------------------

    private val bicCountries: Set<String> = IbanValidator.lengths.keys + setOf("US", "CA", "JP", "CN", "AU", "NZ", "IN", "HK", "SG", "ZA", "MX", "AR", "RU", "KR")

    private fun findBics(line: SourceLine, mask: Mask) {
        for (m in P.BIC.findAll(line.text)) {
            if (!mask.free(m.range)) continue
            val code = m.groupValues[2].uppercase()
            val valid = if (code.substring(4, 6) in bicCountries) Validation.Valid else Validation.Invalid("unknown country ${code.substring(4, 6)}")
            mask.add(m.range)
            add(line, m.range, CandidateKind.BIC, m.groupValues[2], code, label = m.groupValues[1].uppercase(), validation = valid)
        }
    }

    // References ---------------------------------------------------------------------

    private fun collapse(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun acceptRefValue(rule: RefRule, value: String): Boolean {
        val v = value.trim()
        if (v.length < rule.minLen || v.none { it.isDigit() }) return false
        if (P.NUM_DATE.matches(v) || P.ISO_DATE.matches(v)) return false
        if (v.length >= 4 && IbanValidator.lengths.containsKey(v.take(2).uppercase()) && v.filter { it.isLetterOrDigit() }.length in 15..34 &&
            v.substring(2, 4).all { it.isDigit() }
        ) return false
        return true
    }

    private fun extendValue(text: String, valueEnd: Int, first: String): Pair<String, Int> {
        var value = first
        var end = valueEnd
        val token = Regex("^ ([A-Za-z0-9/\\-]+)")
        while (true) {
            val tm = token.find(text.substring(end)) ?: break
            val t = tm.groupValues[1]
            if (t.none { it.isDigit() } || t.length > 12 || value.length + 1 + t.length > 30) break
            value += " $t"
            end += tm.value.length
        }
        return value to end
    }

    private fun addRef(line: SourceLine, range: IntRange, rule: RefRule, label: String, value: String, evidence: String = line.text) {
        val v = collapse(value)
        add(
            line, range, CandidateKind.REFERENCE, v, v, evidence, label = collapse(label).trimEnd(':', ' '),
            subtype = rule.subtype, validation = ReferenceValidator.validate(rule.subtype, v),
        )
    }

    private fun findReferences(line: SourceLine, next: SourceLine?, mask: Mask) {
        val text = line.text
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
                addRef(line, vRange, rule, m.groupValues[1], value)
            }
            // label at the end of the line, value in the cell to the right or the line below
            val lo = rule.labelOnly.find(text)
            if (lo != null) {
                val cont = continuation(line, next)?.text
                val source = rowNeighbour(line, left = false) ?: cont ?: continue
                val vm = rule.valueAtStart.find(source) ?: continue
                var value = vm.groupValues[1]
                if (rule.extend) value = extendValue(source, vm.groups[1]!!.range.last + 1, value).first
                if (!acceptRefValue(rule, value)) continue
                val r = lo.range
                if (!mask.free(r)) continue
                mask.add(r)
                addRef(line, r, rule, lo.groupValues[1], value, evidence = "${text} $source".trim())
            }
        }
    }

    // Amounts -----------------------------------------------------------------------

    private fun findAmounts(line: SourceLine, mask: Mask) {
        val text = line.text
        for (m in P.AMOUNT.findAll(text)) {
            if (!mask.free(m.range)) continue
            val sign = m.groupValues[1]
            val c1 = m.groupValues[2]
            val num = m.groupValues[3]
            val dash = m.groupValues[4]
            val c2 = m.groupValues[5]
            val currencyToken = c1.ifEmpty { c2 }
            val after = text.substring(m.range.last + 1)
            if (currencyToken.isEmpty()) {
                if (dash.isNotEmpty() || !Regex(",\\d{2}$").containsMatchIn(num)) continue
                if (P.AMOUNT_NOT_MONEY_AFTER.containsMatchIn(after)) continue
            } else if (c2.isNotEmpty() && P.PER_UNIT_AFTER.containsMatchIn(after)) {
                continue
            } else if (currencyToken.isNotEmpty() && P.AMOUNT_NOT_MONEY_AFTER.containsMatchIn(after) &&
                after.trimStart().startsWith("%")
            ) continue
            val money = AmountParser.parse(num, currencyToken.ifEmpty { null }, sign.isNotEmpty()) ?: continue
            mask.add(m.range)
            add(
                line, m.range, CandidateKind.AMOUNT, m.value.trim(), money.canonical(),
                validation = if (money.currencyExplicit) Validation.Valid else Validation.Unchecked,
                attrs = mapOf(
                    "cents" to money.cents.toString(),
                    "currency" to money.currency,
                    "currencyExplicit" to money.currencyExplicit.toString(),
                    "numberText" to num,
                ),
            )
        }
    }

    // Phones, e-mails ------------------------------------------------------------------

    private fun findPhones(line: SourceLine, mask: Mask) {
        val text = line.text
        fun emit(range: IntRange, label: String, number: String) {
            if (!mask.free(range)) return
            val digits = number.count { it.isDigit() }
            mask.add(range)
            add(
                line, range, CandidateKind.PHONE, number.trim(), collapse(number), label = label.trimEnd('.', ':', ' '),
                validation = if (digits in 6..15) Validation.Valid else Validation.Invalid("$digits digits"),
            )
        }
        for (m in P.PHONE_LABELLED.findAll(text)) emit(m.groups[2]!!.range, m.groupValues[1], m.groupValues[2])
        // label alone in its cell, number in the cell to the right ("Telefon:" | "0800 555 0199")
        P.PHONE_LABEL_ONLY.find(text)?.let { lm ->
            val source = rowNeighbour(line, left = false) ?: return@let
            val nm = P.PHONE_VALUE.find(source) ?: return@let
            if (!mask.free(lm.range)) return@let
            val number = nm.groupValues[1]
            mask.add(lm.range)
            add(
                line, lm.range, CandidateKind.PHONE, number.trim(), collapse(number), evidence = "$text $source".trim(),
                label = lm.groupValues[1].trimEnd('.', ':', ' '),
                validation = if (number.count { it.isDigit() } in 6..15) Validation.Valid else Validation.Invalid("implausible phone number"),
            )
        }
        for (m in P.PHONE_UNTER.findAll(text)) emit(m.groups[1]!!.range, "unter", m.groupValues[1])
    }

    private fun findEmails(line: SourceLine, mask: Mask) {
        for (m in P.EMAIL.findAll(line.text)) {
            if (!mask.free(m.range)) continue
            mask.add(m.range)
            add(line, m.range, CandidateKind.EMAIL, m.value, m.value.lowercase(), validation = Validation.Valid)
        }
    }

    // Names -------------------------------------------------------------------------------

    private fun stripSalutation(s: String): Pair<String, Boolean> {
        val m = P.SALUTATION.find(s) ?: return s to false
        return s.substring(m.range.last + 1).trim() to true
    }

    private fun isOrg(s: String) = P.ORG_WORDS.containsMatchIn(s)

    private fun looksLikePerson(s: String, salutationSeen: Boolean): Boolean {
        if (s.length > 60 || s.any { it.isDigit() } || s.isBlank()) return false
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val words = tokens.filter { it.lowercase() !in P.CONNECTORS }
        if (words.isEmpty() || tokens.last().lowercase() in P.CONNECTORS) return false
        if (!words.all { w -> w[0].isUpperCase() && w.all { it.isLetter() || it in "'\u2019.-" } }) return false
        return words.size >= 2 || salutationSeen
    }

    private fun looksLikeAddressLine(t: String): Boolean =
        P.POSTAL.containsMatchIn(t) || P.STREET.matches(t) || t.startsWith("Postfach", true) ||
            Regex("(?i)^\\d+\\.?\\s*(?:OG|Etage|Stock)\\b.*").matches(t) || t.none { it.isLetter() }

    private fun nameDraft(line: SourceLine, name: String, label: String, extra: Map<String, String>): Boolean {
        val (stripped, sal) = stripSalutation(name.trim().trimEnd(',', ';'))
        if (stripped.isBlank()) return false
        val start = line.text.indexOf(stripped).coerceAtLeast(0)
        val range = start until (start + stripped.length)
        val attrs = if (sal) extra + ("salutation" to "true") else extra
        return when {
            isOrg(stripped) -> {
                add(line, range, CandidateKind.ORG_NAME, stripped, stripped, label = label, attrs = attrs)
                true
            }
            looksLikePerson(stripped, sal) -> {
                add(line, range, CandidateKind.PERSON_NAME, stripped, stripped, label = label, attrs = attrs)
                true
            }
            else -> false
        }
    }

    private fun findNames(line: SourceLine) {
        val t = line.text
        val zoneAttr = line.zone?.let { mapOf("zone" to it.name) } ?: emptyMap()
        // explicit routing lines, in any zone
        P.ROUTE_ZHD.find(t)?.let { if (nameDraft(line, it.groupValues[2], collapse(it.groupValues[1]), zoneAttr + ("routing" to "true"))) return }
        P.ROUTE_CO.find(t)?.let { if (nameDraft(line, it.groupValues[2], "c/o", zoneAttr + ("routing" to "true"))) return }
        P.ROUTE_GUARDIAN.find(t)?.let {
            if (nameDraft(line, it.groupValues[2], collapse(it.groupValues[1]), zoneAttr + ("guardianOf" to "true"))) return
        }
        when (line.zone) {
            BlockZone.ADDRESS_FIELD -> {
                if (looksLikeAddressLine(t)) return
                // "Familie" / "Herrn" on the line above lets a single surname stand as a name.
                val prev = lines.getOrNull(line.order - 1)
                    ?.takeIf { it.page == line.page && it.zone == line.zone }
                    ?.text
                val prevIsFamily = prev != null && Regex("(?i)^(?:Familie|Fam\\.)$").matches(prev)
                val prevIsSalutation = prev != null && !prevIsFamily && P.SALUTATION.containsMatchIn(prev) && stripSalutation(prev).first.isBlank()
                val guardianMarker = prev?.let { Regex("(?i)^(?:Erziehungsberechtigte[rn]?|Eltern|Vormund|Sorgeberechtigte[rn]?)\\s+(?:von|des|der)$").find(it)?.value }
                when {
                    guardianMarker != null && nameDraft(line, t, collapse(guardianMarker), zoneAttr + ("guardianOf" to "true")) -> Unit
                    prevIsFamily && t.split(Regex("\\s+")).size == 1 && !isOrg(t) ->
                        add(line, 0 until t.length, CandidateKind.PERSON_NAME, "Familie $t", "Familie $t", attrs = zoneAttr)
                    prevIsSalutation && t.split(Regex("\\s+")).size == 1 && !isOrg(t) ->
                        add(line, 0 until t.length, CandidateKind.PERSON_NAME, t, t, attrs = zoneAttr + ("salutation" to "true"))
                    else -> nameDraft(line, t, "", zoneAttr)
                }
            }
            BlockZone.RETURN_ADDRESS -> {
                val first = t.split(Regex("\\s*[\u00B7\u2022|]\\s*|\\s+[\u2013\u2014-]\\s+")).firstOrNull { it.isNotBlank() } ?: return
                if (first.any { it.isDigit() }) return
                if (!nameDraft(line, first, "", zoneAttr)) {
                    add(line, 0 until first.length, CandidateKind.ORG_NAME, first.trim(), first.trim(), attrs = zoneAttr + ("guess" to "true"))
                }
            }
            BlockZone.LETTERHEAD -> {
                if (looksLikeAddressLine(t) || t.count { it.isDigit() } >= 3 ||
                    Regex("(?i)(?:Tel|Fax|IBAN|BIC|USt|Steuer|HRB|HRA|Gesch\u00E4ftsf|www\\.|@)").containsMatchIn(t)
                ) return
                if (isOrg(t)) {
                    add(line, 0 until t.length, CandidateKind.ORG_NAME, t, t, attrs = zoneAttr)
                } else if (drafts.none { it.line.page == line.page && it.line.blockIndex == line.blockIndex && it.c.attrs["zone"] == "LETTERHEAD" } &&
                    t.length in 3..60
                ) {
                    add(line, 0 until t.length, CandidateKind.ORG_NAME, t, t, attrs = zoneAttr + ("guess" to "true"))
                }
            }
            null -> Unit
        }
    }

    // ── labels ────────────────────────────────────────────────────────────────────────

    private fun labelDrafts() {
        val byLine = drafts.groupBy { it.line }
        for ((line, list) in byLine) {
            val numeric = list.filter {
                it.c.kind in setOf(
                    CandidateKind.DATE, CandidateKind.DATETIME, CandidateKind.RELATIVE_DEADLINE, CandidateKind.AMOUNT,
                    CandidateKind.IBAN, CandidateKind.REFERENCE, CandidateKind.PHONE,
                )
            }
            var previousDate: Draft? = null
            for (d in numeric) {
                val kind = d.c.kind
                val prevEnd = numeric.filter { it !== d && it.end <= d.start }.maxOfOrNull { it.end } ?: 0
                val nextStart = numeric.filter { it !== d && it.start >= d.end }.minOfOrNull { it.start } ?: line.text.length
                val pre = line.text.substring(prevEnd, d.start)
                val post = line.text.substring(d.end, minOf(nextStart, d.end + 30))
                when (kind) {
                    CandidateKind.AMOUNT -> labelAmount(d, line, pre, post, prevEnd == 0)
                    CandidateKind.DATE, CandidateKind.DATETIME -> {
                        labelDate(d, line, pre, post, prevEnd == 0, previousDate)
                        previousDate = d
                    }
                    CandidateKind.RELATIVE_DEADLINE -> {
                        val hit = LabelDetector.detectLast(LabelDetector.lastSentence(pre.takeLast(160)), LabelDetector.RELATIVE_RULES)
                        d.c = d.c.copy(label = hit?.text.orEmpty(), labelKind = hit?.kind ?: LabelKind.DEADLINE)
                    }
                    else -> Unit
                }
            }
        }
    }

    private val netBase = Regex("(?i)(?:mwst|ust|umsatzsteuer|mehrwertsteuer|vat)\\.?\\s*(?:auf|on|of|von)\\s*$")

    private fun labelAmount(d: Draft, line: SourceLine, pre: String, post: String, firstInLine: Boolean) {
        val preSentence = LabelDetector.lastSentence(pre.takeLast(80))
        var hit: LabelHit? = null
        val nb = netBase.find(preSentence)
        if (nb != null) hit = LabelHit(nb.value.trim(), LabelKind.NET, nb.range.first, nb.range.last + 1)
        if (hit == null) hit = LabelDetector.detectLast(preSentence, LabelDetector.AMOUNT_RULES)
        if (hit == null) {
            val p = LabelDetector.detectFirst(LabelDetector.firstSentence(post), LabelDetector.AMOUNT_RULES)
            if (p != null && p.start <= 12) hit = p
        }
        if (hit == null && firstInLine && pre.none { it.isLetter() }) {
            rowNeighbour(line, left = true)?.let { nt ->
                hit = LabelDetector.detectLast(LabelDetector.lastSentence(nt.takeLast(80)), LabelDetector.AMOUNT_RULES)
            }
        }
        d.c = d.c.copy(label = hit?.text.orEmpty(), labelKind = hit?.kind)
    }

    private fun labelDate(d: Draft, line: SourceLine, pre: String, post: String, firstInLine: Boolean, previousDate: Draft?) {
        val preSentence = LabelDetector.lastSentence(pre.takeLast(80))
        var hit = LabelDetector.detectLast(preSentence, LabelDetector.DATE_RULES)
        if (hit == null) {
            val p = LabelDetector.detectFirst(LabelDetector.firstSentence(post), LabelDetector.DATE_RULES)
            if (p != null && p.start <= 12) hit = p
        }
        if (hit == null && firstInLine && pre.none { it.isLetter() }) {
            rowNeighbour(line, left = true)?.let { nt ->
                hit = LabelDetector.detectLast(LabelDetector.lastSentence(nt.takeLast(80)), LabelDetector.DATE_RULES)
            }
        }
        var label = hit?.text.orEmpty()
        var kind = hit?.kind
        // "01.10.2026 - 31.12.2026" / "01.01.2025 bis 31.12.2025"
        if (previousDate != null && Regex("(?i)^\\s*(?:[-\u2013\u2014]|bis|to|until)\\s*$").matches(pre)) {
            kind = LabelKind.PERIOD
            if (label.isEmpty()) label = previousDate.c.label
            if (previousDate.c.labelKind != LabelKind.PERIOD) previousDate.c = previousDate.c.copy(labelKind = LabelKind.PERIOD)
        }
        val trimmedPre = pre.trim()
        if (kind == null && firstInLine && line.page == 1 && d.c.kind == CandidateKind.DATE &&
            P.PLACE_COMMA.matches(trimmedPre) && !P.WEEKDAY.matches(trimmedPre)
        ) {
            kind = LabelKind.LETTER_DATE
            label = "Ort, Datum"
        }
        if (kind == null && d.c.kind == CandidateKind.DATETIME && d.c.attrs["timeOnly"] == null) kind = LabelKind.APPOINTMENT
        d.c = d.c.copy(label = label, labelKind = kind)
    }

    // ── validation ────────────────────────────────────────────────────────────────────

    private fun parseYmd(normalized: String): Triple<Int, Int, Int>? {
        if (normalized.length < 10 || normalized[0] == 'T') return null
        val p = normalized.substring(0, 10).split('-')
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    private fun inferLetterDate(): LocalDate? {
        for (d in drafts) {
            if (d.c.kind != CandidateKind.DATE && d.c.kind != CandidateKind.DATETIME) continue
            if (d.c.page != 1 || d.c.labelKind !in setOf(LabelKind.LETTER_DATE, LabelKind.INVOICE_DATE)) continue
            val (y, m, dd) = parseYmd(d.c.normalized) ?: continue
            if (y !in 2000..2100 || !DateValidator.isRealDate(y, m, dd)) continue
            return LocalDate.of(y, m, dd)
        }
        return null
    }

    private fun validateDates(letter: LocalDate?) {
        for (d in drafts) {
            if (d.c.kind != CandidateKind.DATE && d.c.kind != CandidateKind.DATETIME) continue
            if (d.c.attrs["timeOnly"] != null) continue
            val (y, m, dd) = parseYmd(d.c.normalized) ?: continue
            val lk = d.c.labelKind
            val verdict = when {
                lk == LabelKind.BIRTH_DATE ->
                    if (DateValidator.isRealDate(y, m, dd)) Validation.Unchecked else DateValidator.validate(y, m, dd, null)
                givenLetterDate == null && (lk == LabelKind.LETTER_DATE || lk == LabelKind.INVOICE_DATE) ->
                    if (!DateValidator.isRealDate(y, m, dd)) DateValidator.validate(y, m, dd, null)
                    else if (y in 2000..2100) Validation.Valid
                    else Validation.Invalid("implausible letter date year $y")
                else -> {
                    val past = when (lk) {
                        LabelKind.PERIOD, LabelKind.REFERENCED_DATE -> 10L
                        else -> 1L
                    }
                    DateValidator.validate(y, m, dd, letter, past)
                }
            }
            d.c = d.c.copy(validation = verdict)
        }
    }

    private fun markTriples() {
        val amounts = drafts.filter { it.c.kind == CandidateKind.AMOUNT }
        val nets = amounts.filter { it.c.labelKind == LabelKind.NET }
        val vats = amounts.filter { it.c.labelKind == LabelKind.VAT }
        val grosses = amounts.filter { it.c.labelKind == LabelKind.GROSS || it.c.labelKind == LabelKind.TOTAL_DUE }
        var k = 0
        for (n in nets) for (v in vats) for (g in grosses) {
            val nc = n.c.cents ?: continue
            val vc = v.c.cents ?: continue
            val gc = g.c.cents ?: continue
            if (nc <= 0 || vc <= 0 || n.c.currency != g.c.currency || v.c.currency != g.c.currency) continue
            if (!AmountConsistency.netPlusVatEqualsGross(nc, vc, gc)) continue
            k++
            for (x in listOf(n, v, g)) {
                x.c = x.c.copy(
                    validation = if (x.c.validation.isInvalid) x.c.validation else Validation.Valid,
                    attrs = x.c.attrs + ("triple" to "T$k"),
                )
            }
        }
    }

    // ── ids ───────────────────────────────────────────────────────────────────────────────

    private fun assignIds(): List<Candidate> {
        val counters = HashMap<String, Int>()
        return drafts.map { d ->
            val prefix = when (d.c.kind) {
                CandidateKind.DATE -> "D"
                CandidateKind.DATETIME -> "DT"
                CandidateKind.RELATIVE_DEADLINE -> "R"
                CandidateKind.AMOUNT -> "A"
                CandidateKind.IBAN -> "I"
                CandidateKind.BIC -> "B"
                CandidateKind.REFERENCE -> "N"
                CandidateKind.PERSON_NAME -> "P"
                CandidateKind.ORG_NAME -> "O"
                CandidateKind.PHONE -> "T"
                CandidateKind.EMAIL -> "E"
            }
            val n = (counters[prefix] ?: 0) + 1
            counters[prefix] = n
            d.c.copy(id = "$prefix$n")
        }
    }
}
