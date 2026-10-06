package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind

/**
 * The tiny GBNF grammars of the questionnaire: one per question, each accepting only what that
 * question may be answered with. Answers are short plain text, not JSON: words separated by single
 * spaces, a quoted string where the model must write text, and `; ` between the entries of a list.
 * ```
 * type      bill de HIGH
 * party     M1 COMPANY "Stadtwerke Beispiel" HIGH        (addressee adds a relation: M2 PERSON HOUSEHOLD "Familie B" HIGH)
 * amount    A3 TOTAL_DUE HIGH          date  D1 LETTER_DATE HIGH          deadline  RULE "within 14 days" DEADLINE MEDIUM
 * iban/ref  I1 HIGH                    refs  R1 R2 MEDIUM                 none      NONE
 * extras    N4 "Zählernummer" meter_number "" MEDIUM; NONE "Klasse" school_class "2a" MEDIUM
 * ```
 * Every id in a grammar is one of the offered candidates of the right kind, so a value the model would
 * have to type itself (an amount, a date, an IBAN) cannot be written at all; what is written text (a
 * name, a rule, an extra's label and value) is checked against the OCR text afterwards, by the same
 * verifier as the single-call reading. A word is one token to a small model, which is why confidence is
 * LOW, MEDIUM or HIGH here as well.
 */
object QuestionGrammars {

    const val NONE = StructuredGrammar.NONE

    /** The word the model writes in front of a quoted period for a relative deadline. */
    const val RULE = "RULE"

    /** Separates the entries of a list answer (co-addressees, extras). */
    const val LIST_SEPARATOR = "; "

    /** How many addressees one answer may name (the parser truncates; the grammar does not count). */
    const val MAX_ADDRESSEES = 3

    private fun word(s: String) = "\"$s\""

    private fun words(values: List<String>) = values.joinToString(" | ") { word(it) }

    private fun render(vararg rules: Pair<String, String>): String {
        val all = LinkedHashMap<String, String>()
        rules.forEach { (name, body) -> all[name] = body }
        all["qstr"] = GrammarSyntax.string(nonEmpty = true)
        all["qstr0"] = GrammarSyntax.string(nonEmpty = false)
        all.putAll(GrammarSyntax.commonRules())
        return GrammarSyntax.render(all)
    }

    private val CONF = "conf" to words(StructuredGrammar.CONFIDENCE_WORDS)

    /** Document type, language code and confidence: `bill de HIGH`. */
    fun type(schema: ExtractionSchema): String = render(
        "root" to "tid \" \" lang \" \" conf",
        "tid" to words(schema.families.map { it.id }),
        "lang" to LANG,
        CONF,
    )

    /**
     * One or several parties over the name candidates [nameIds] (or a quoted name), or NONE.
     * `id KIND ["REL"] "name" CONFIDENCE`, with entries joined by `; ` when [list].
     */
    fun party(nameIds: List<String>, withRelation: Boolean, list: Boolean): String {
        val ref = (nameIds.map { word(it) } + "qstr").joinToString(" | ")
        val entry = if (withRelation) {
            "ref \" \" kind \" \" rel \" \" qstr \" \" conf"
        } else {
            "ref \" \" kind \" \" qstr \" \" conf"
        }
        return render(
            "root" to (if (list) "${word(NONE)} | entry (\"$LIST_SEPARATOR\" entry)*" else "${word(NONE)} | entry"),
            "entry" to entry,
            "ref" to ref,
            "kind" to words(StructuredGrammar.PARTY_KINDS),
            "rel" to words(StructuredGrammar.PARTY_RELATIONS),
            CONF,
        )
    }

    /**
     * One value slot of [slot]'s kind over the offered candidates of that kind, or NONE. Null when there
     * is nothing to choose from (no candidate of the kind and no way to quote), so the caller does not
     * ask at all: the answer is NONE.
     */
    fun slot(slot: SlotKey, offered: OfferedCandidates): String? {
        val ids = offered.idsOf(*slot.kind.candidates)
        fun idRule() = "id" to ids.joinToString(" | ") { word(it) }
        return when (slot.kind) {
            SlotKind.AMOUNT, SlotKind.DATE -> {
                if (ids.isEmpty()) return null
                val roles = if (slot.kind == SlotKind.AMOUNT) Roles.AMOUNT else Roles.DATE
                render("root" to "${word(NONE)} | id \" \" role \" \" conf", idRule(), "role" to words(roles), CONF)
            }
            SlotKind.DEADLINE -> {
                val alternatives = mutableListOf(word(NONE), "${word(RULE)} \" \" qstr \" \" role \" \" conf")
                if (ids.isNotEmpty()) alternatives += "id \" \" role \" \" conf"
                render(
                    "root" to alternatives.joinToString(" | "),
                    "id" to ids.ifEmpty { listOf(NONE) }.joinToString(" | ") { word(it) },
                    "role" to words(Roles.DATE),
                    CONF,
                )
            }
            SlotKind.IBAN, SlotKind.REFERENCE -> {
                if (ids.isEmpty()) return null
                render("root" to "${word(NONE)} | id \" \" conf", idRule(), CONF)
            }
            SlotKind.REFERENCE_LIST -> {
                if (ids.isEmpty()) return null
                render("root" to "${word(NONE)} | id (\" \" id)* \" \" conf", idRule(), CONF)
            }
            SlotKind.NAME -> render(
                "root" to "${word(NONE)} | ref \" \" conf",
                "ref" to (ids.map { word(it) } + "qstr").joinToString(" | "),
                CONF,
            )
            SlotKind.ACTION -> render(
                "root" to "${word(NONE)} | act \" \" conf",
                "act" to words(SlotKey.ACTIONS),
                CONF,
            )
        }
    }

    /**
     * The other important facts: `id "label" key "value" CONFIDENCE`, several joined by `; `, or NONE. [ids]
     * are the candidates no slot or party took; an entry with no candidate writes NONE and quotes the value.
     */
    fun extras(ids: List<String>): String = render(
        "root" to "${word(NONE)} | entry (\"$LIST_SEPARATOR\" entry)*",
        *extraEntryRules(ids),
    )

    /** The language of the letter alone: a BCP-47 code (`de`, `pt-BR`). */
    fun language(): String = render("root" to LANG)

    /** `[a-z]{2,3}` with an optional region or script subtag, as BCP-47 writes it (written out: no `{m,n}`). */
    private const val LANG = "[a-z] [a-z] [a-z]? (\"-\" [A-Za-z0-9]+)?"

    private fun extraEntryRules(ids: List<String>): Array<Pair<String, String>> = arrayOf(
        "entry" to "xid \" \" qstr \" \" xkey \" \" qstr0 \" \" conf",
        "xid" to (ids + NONE).joinToString(" | ") { word(it) },
        "xkey" to "[a-z] [a-z_]+",
        CONF,
    )

    /** One quoted line of text. */
    fun line(): String = render("root" to "qstr")

    /** Three quoted lines separated by spaces. */
    fun threeLines(): String = render("root" to "qstr \" \" qstr \" \" qstr")

    /** The action lines: `NONE` (the reader has nothing to do), or one to three quoted lines separated by spaces. */
    fun actionLines(): String = render("root" to "${word(NONE)} | qstr (\" \" qstr (\" \" qstr)?)?")

    /** The ids the extras question may offer: every candidate kind, minus [taken]. */
    fun remainingIds(offered: OfferedCandidates, taken: Set<String>): List<String> =
        offered.idsOf(*CandidateKind.entries.toTypedArray()).filter { it !in taken }
}
