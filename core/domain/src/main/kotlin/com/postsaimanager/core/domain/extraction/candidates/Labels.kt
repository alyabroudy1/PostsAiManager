package com.postsaimanager.core.domain.extraction.candidates

/** A label phrase found in text and what it means. */
internal data class LabelHit(val text: String, val kind: LabelKind, val start: Int, val end: Int)

internal class LabelRule(val kind: LabelKind, pattern: String) {
    val regex = Regex("(?<![\\p{L}\\d])(?:$pattern)(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
}

/**
 * Finds the label nearest to a value. Rules are plain phrase patterns; when several match the
 * one ending closest to the value wins, and at the same end the longer phrase wins (so
 * "zahlbar bis" beats "bis", "offener Gesamtbetrag" beats "Gesamtbetrag").
 */
internal object LabelDetector {

    private fun rules(vararg pairs: Pair<LabelKind, String>) = pairs.map { LabelRule(it.first, it.second) }

    val AMOUNT_RULES: List<LabelRule> = rules(
        LabelKind.NET to "Netto(?:betrag|summe|preis)?|net(?:\\s+(?:amount|total))?|Zwischensumme|subtotal",
        LabelKind.VAT to "MwSt\\.?|Mehrwertsteuer|USt\\.?|Umsatzsteuer|VAT|Steuerbetrag|\\p{L}*steuer\\s+\\d+\\s*%",
        LabelKind.GROSS to "Brutto(?:betrag|summe)?|gross|Gesamt(?:betrag|summe|preis|forderung|kosten)?|Endbetrag|Endsumme|Summe|" +
            "Rechnungsbetrag|Rechnungssumme|total(?:\\s+amount)?",
        LabelKind.TOTAL_DUE to "zu\\s+zahlen(?:der\\s+Betrag)?|zu\\s+\u00FCberweisen|Zahlbetrag|Zahlungsbetrag|Zahllast|" +
            "Nachzahlung(?:sbetrag)?|Abschlusszahlung|Restbetrag|offene[rnms]?\\s+(?:Gesamt)?(?:betrag|forderung|saldo|posten)|" +
            "Forderung|outstanding(?:\\s+balance)?|overdue(?:\\s+(?:balance|amount))?|amount\\s+due|balance(?:\\s+due)?|Saldo|" +
            "f\u00E4lliger\\s+Betrag|\u0627\u0644\u0645\u0628\u0644\u063A\\s+\u0627\u0644\u0645\u0633\u062A\u062D\u0642",
        LabelKind.CREDIT to "Guthaben|Erstattung(?:sbetrag)?|R\u00FCckzahlung|Gutschrift|refund|credit",
        LabelKind.ADVANCE to "Abschlag\\p{L}*|\\p{L}*[Vv]orauszahlung\\p{L}*|Vorschuss|Anzahlung|instal?ment|" +
            "monthly\\s+(?:payment|instal?ment)|advance",
        LabelKind.FEE to "Mahngeb\u00FChr|Geb\u00FChr(?:en)?|S\u00E4umniszuschlag|Zuschlag|Verzugszinsen|Zinsen|" +
            "Bearbeitungsgeb\u00FChr|(?:late\\s+)?fee|charge|Portokosten|Versandkosten",
        LabelKind.PREMIUM to "\\p{L}*beitrag|Pr\u00E4mie|premium|Beitragsanpassung",
        LabelKind.TAX_ASSESSED to "festgesetzte[nrms]?\\s+\\p{L}*steuer|\\p{L}*steuer\\s+festgesetzt|Einkommensteuer|Steuerschuld",
        LabelKind.COST to "Kosten|Betriebskosten|Preis|price|Entgelt|costs?",
        LabelKind.PREVIOUS to "bisher\\p{L}*|fr\u00FCher\\p{L}*|previous(?:ly)?|vorher|vormals",
        LabelKind.GENERIC_AMOUNT to "Betrag|Betr\\.|amount|sum",
    )

    val DATE_RULES: List<LabelRule> = rules(
        LabelKind.BIRTH_DATE to "geb\\.?|geboren|Geburtsdatum|Geburtstag|date\\s+of\\s+birth|DOB",
        LabelKind.INVOICE_DATE to "Rechnungsdatum|Belegdatum|invoice\\s+date",
        LabelKind.DUE_DATE to "zahlbar\\s+(?:bis|am|sp\u00E4testens)(?:\\s+zum)?|f\u00E4llig(?:\\s+(?:am|bis|zum))?|F\u00E4lligkeit\\p{L}*|" +
            "Zahlungsziel|zu\\s+zahlen\\s+bis|zahlen\\s+Sie\\s+bis(?:\\s+zum)?|due(?:\\s+(?:on|by|date))?|" +
            "payable\\s+(?:by|until)|payment\\s+due",
        LabelKind.LETTER_DATE to "\\p{L}*datum|date|Briefdatum|\u0627\u0644\u062A\u0627\u0631\u064A\u062E",
        LabelKind.DEADLINE to "Frist(?:\\s+bis)?|Einspruchsfrist|sp\u00E4testens(?:\\s+(?:bis|zum|am))?|bis\\s+sp\u00E4testens|bis\\s+zum|bis|" +
            "R\u00FCckgabe(?:\\s+bis)?|reply(?:\\s+to\\s+this\\s+letter)?\\s+by|respond\\s+by|(?:no\\s+)?later\\s+than|until|by|" +
            "deadline|Antwort\\s+bis|zugehen|\u0645\u0648\u0639\u062F\\s+\u0623\u0642\u0635\u0627\u0647",
        LabelKind.APPOINTMENT to "Termin\\p{L}*|appointment|Sprechstunde|Vorsorge\\p{L}*|Untersuchung",
        LabelKind.EFFECTIVE_FROM to "g\u00FCltig\\s+ab(?:\\s+dem)?|wirksam\\s+ab(?:\\s+dem)?|ab(?:\\s+dem)?|from|" +
            "starting(?:\\s+(?:on|from))?|effective(?:\\s+from)?|starts?|beginnt(?:\\s+am)?|Vertragsbeginn|Beginn|" +
            "first\\s+instal?ment\\s+on",
        LabelKind.CONTRACT_END to "Vertragsende|Laufzeitende|endet(?:\\s+\\p{L}+){0,3}\\s+(?:zum|am)|l\u00E4uft\\s+(?:zum|am)|" +
            "ends?\\s+on|expires?(?:\\s+on)?",
        LabelKind.EVENT_DATE to "\\p{L}*ausflug|Veranstaltung|Reise|Abfahrt|event|Feier|Sommerfest",
        LabelKind.PERIOD to "\\p{L}*zeitraum|period",
        LabelKind.REFERENCED_DATE to "vom|dated",
    )

    /** For relative deadlines: what the sentence before the phrase is about. */
    val RELATIVE_RULES: List<LabelRule> = rules(
        LabelKind.DUE_DATE to "zahlbar|zahlen|\u00FCberweis\\p{L}*|zahlung|pay\\p{L}*",
        LabelKind.OBJECTION to "Einspruch|Widerspruch|objection|appeal|Klage",
        LabelKind.DEADLINE to "Frist|deadline|antworten|reply|r\u00FCckmeldung|melden",
    )

    /** The rule match ending nearest the end of [text]; null when none. */
    fun detectLast(text: String, rules: List<LabelRule>): LabelHit? {
        var best: LabelHit? = null
        var bestRank = -1
        rules.forEachIndexed { idx, rule ->
            for (m in rule.regex.findAll(text)) {
                val hit = LabelHit(m.value.trim(), rule.kind, m.range.first, m.range.last + 1)
                val better = best == null ||
                    hit.end > best!!.end ||
                    (hit.end == best!!.end && (hit.end - hit.start) > (best!!.end - best!!.start)) ||
                    (hit.end == best!!.end && (hit.end - hit.start) == (best!!.end - best!!.start) && idx < bestRank)
                if (better) {
                    best = hit
                    bestRank = idx
                }
            }
        }
        return best
    }

    /** The rule match starting nearest the start of [text]; null when none. */
    fun detectFirst(text: String, rules: List<LabelRule>): LabelHit? {
        var best: LabelHit? = null
        for (rule in rules) {
            for (m in rule.regex.findAll(text)) {
                val hit = LabelHit(m.value.trim(), rule.kind, m.range.first, m.range.last + 1)
                if (best == null || hit.start < best!!.start ||
                    (hit.start == best!!.start && (hit.end - hit.start) > (best!!.end - best!!.start))
                ) {
                    best = hit
                }
            }
        }
        return best
    }

    private val SENTENCE_BREAK = Regex("(?<=[.!?;])\\s+(?=\\p{Lu})")

    /** The text after the last sentence break in [pre]. */
    fun lastSentence(pre: String): String {
        val m = SENTENCE_BREAK.findAll(pre).lastOrNull() ?: return pre
        return pre.substring(m.range.last + 1)
    }

    /** The text up to the first sentence break in [post]. */
    fun firstSentence(post: String): String {
        val m = SENTENCE_BREAK.find(post) ?: return post
        return post.substring(0, m.range.first)
    }
}
