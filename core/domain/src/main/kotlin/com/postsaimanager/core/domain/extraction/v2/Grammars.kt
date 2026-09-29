package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind

/**
 * The GBNF for call 1, the structured reading, generated from what was actually found. Together
 * with [TextGrammar] this is the only place that knows the grammar syntax.
 *
 * The answer is one JSON object that starts with the document type. The grammar has one
 * alternative per type, so the slots that follow depend on the type the model just wrote (see
 * [DocType.slots]) and the sampler physically cannot write a slot the type does not have.
 *
 * Every value slot is restricted to the ids of the offered candidates of the right kind, or
 * `"NONE"`; a value the model would have to type itself (an amount, a date, an IBAN) cannot be
 * written at all. Amounts and dates also carry the model's own reading of what they are (an enum),
 * which the verifier compares with the slot. Only a party's name, a relative deadline rule and an
 * extra's label and value may be written text, and those are checked against the OCR text afterwards.
 *
 * **Confidence** is `LOW`, `MEDIUM` or `HIGH` on every object the model decides (type, party, slot,
 * extra), key `"c"`. A word is one token and a small model picks between three words reliably; a
 * 0-100 number costs two or three tokens and, measured on earlier models, collapses to "90" for
 * everything, so its extra resolution is not information. [ConfidenceCombiner] turns the words into
 * numbers and combines them with the code's checks.
 *
 * Keys are short on purpose: every token of the answer is decoded on a phone (target: at most about
 * 450 tokens for a typical letter).
 * ```
 * {"type":"bill","tc":"HIGH","lang":"de",
 *  "parties":[{"r":"SENDER","id":"M1","k":"COMPANY","rel":"NONE","c":"HIGH"}, ...],
 *  "s":{"letter_date":{"id":"D1","r":"LETTER_DATE","c":"HIGH"},"total":{"id":"A3","r":"TOTAL_DUE","c":"HIGH"},
 *       "iban":{"id":"I1","c":"HIGH"},"reference":"NONE", ...},
 *  "x":[{"lb":"Zählernummer","k":"meter_number","id":"N4","v":"","c":"MEDIUM"}]}
 * ```
 */
object StructuredGrammar {

    const val NONE = "NONE"
    const val MAX_QUOTE_CHARS = 100
    const val MAX_PARTIES = 6

    /** How many open-metadata entries the model may add, to bound decode time. */
    const val MAX_EXTRAS = 6
    const val MAX_EXTRA_LABEL_CHARS = 40
    const val MAX_EXTRA_VALUE_CHARS = 100

    val CONFIDENCE_WORDS = listOf("LOW", "MEDIUM", "HIGH")
    val PARTY_ROLES = PartyRole.entries.map { it.name }
    val PARTY_KINDS = PartyKind.entries.map { it.name }
    val PARTY_RELATIONS = PartyRelation.entries.map { it.name }

    /** Kinds an extra may point at: everything offered. */
    private val EXTRA_KINDS = CandidateKind.entries.toTypedArray()

    /** How many cited references a list slot keeps; the grammar does not count, the parser truncates. */
    const val MAX_REF_IDS = 4

    /** Longest key of an extra, in characters. The parser truncates. */
    const val MAX_EXTRA_KEY_CHARS = 30

    fun build(offered: OfferedCandidates, schema: ExtractionSchema): String {
        val rules = LinkedHashMap<String, String>()

        // No bounded repetition anywhere (`x{0,5}`, `qchar{1,100}`): llama.cpp expands each into a chain
        // of helper rules, and every token then advances dozens of parser stacks. Lists and strings are
        // unbounded here; the token limit stops a runaway and the parser truncates to the MAX_* caps.
        //
        // The parts every type shares are single rules, so the type alternative is only its own literal
        // and its own slots after the shared core (every type starts with Slots.CORE).
        rules["root"] = schema.types.joinToString(" | ") { "t-" + it.rule() }
        val coreSlots = Slots.CORE
        for (type in schema.types) {
            require(type.slots.take(coreSlots.size) == coreSlots) { "type ${type.id} must start with the core slots" }
            val own = type.slots.drop(coreSlots.size).joinToString("") { " \",\" ws ${slotRule(it)}" }
            rules["t-" + type.rule()] =
                "\"{\" ws ${GrammarSyntax.key("type")} ws ${GrammarSyntax.lit(type.id)} head core$own tail"
        }
        rules["head"] = listOf(
            "\",\" ws ${GrammarSyntax.key("tc")} ws conf",
            "${GrammarSyntax.key("lang")} ws lang",
            "${GrammarSyntax.key("parties")} ws parties",
            "${GrammarSyntax.key("s")} ws \"{\" ws",
        ).joinToString(" \",\" ws ")
        rules["core"] = coreSlots.joinToString(" \",\" ws ") { slotRule(it) }
        rules["tail"] = "ws \"}\" \",\" ws ${GrammarSyntax.key("x")} ws xlist ws \"}\""

        rules["conf"] = GrammarSyntax.enumRule(CONFIDENCE_WORDS)
        rules["lang"] = "\"\\\"\" [a-z] [a-z] [a-z]? (\"-\" [A-Za-z0-9]+)? \"\\\"\""

        rules["parties"] = "\"[\" ws (party (ws \",\" ws party)*)? ws \"]\""
        rules["party"] = GrammarSyntax.obj(
            "r" to "prole", "id" to "nameref", "k" to "pkind", "rel" to "prel", "c" to "conf",
        )
        rules["prole"] = GrammarSyntax.enumRule(PARTY_ROLES)
        rules["pkind"] = GrammarSyntax.enumRule(PARTY_KINDS)
        rules["prel"] = GrammarSyntax.enumRule(PARTY_RELATIONS)

        rules["arole"] = GrammarSyntax.enumRule(Roles.AMOUNT)
        rules["drole"] = GrammarSyntax.enumRule(Roles.DATE)
        rules["action"] = "${GrammarSyntax.lit(NONE)} | " + GrammarSyntax.obj("id" to "actionid", "c" to "conf")
        rules["actionid"] = GrammarSyntax.enumRule(SlotKey.ACTIONS)

        rules["amt"] = valueRule(offered.idsOf(*SlotKind.AMOUNT.candidates), "arole", allowRule = false)
        rules["date"] = valueRule(offered.idsOf(*SlotKind.DATE.candidates), "drole", allowRule = false)
        rules["due"] = valueRule(offered.idsOf(*SlotKind.DEADLINE.candidates), "drole", allowRule = true)
        rules["iban"] = idObject(offered.idsOf(*SlotKind.IBAN.candidates))
        rules["ref"] = idObject(offered.idsOf(*SlotKind.REFERENCE.candidates))
        val refIds = offered.idsOf(*SlotKind.REFERENCE_LIST.candidates)
        rules["refs"] = if (refIds.isEmpty()) {
            GrammarSyntax.lit(NONE)
        } else {
            "${GrammarSyntax.lit(NONE)} | \"{\" ws ${GrammarSyntax.key("ids")} ws \"[\" ws refid (ws \",\" ws refid)* ws \"]\" " +
                "\",\" ws ${GrammarSyntax.key("c")} ws conf ws \"}\""
        }
        if (refIds.isNotEmpty()) rules["refid"] = refIds.joinToString(" | ") { GrammarSyntax.lit(it) }

        val nameIds = offered.idsOf(*SlotKind.NAME.candidates)
        rules["nameref"] = (nameIds.map { GrammarSyntax.lit(it) } + "quote").joinToString(" | ")
        rules["name"] = "${GrammarSyntax.lit(NONE)} | " + GrammarSyntax.obj("id" to "nameref", "c" to "conf")

        val extraIds = offered.idsOf(*EXTRA_KINDS)
        rules["xlist"] = "\"[\" ws (extra (ws \",\" ws extra)*)? ws \"]\""
        rules["extra"] = GrammarSyntax.obj("lb" to "xlabel", "k" to "xkey", "id" to "xid", "v" to "xvalue", "c" to "conf")
        rules["xlabel"] = GrammarSyntax.string(nonEmpty = true)
        rules["xkey"] = "\"\\\"\" [a-z] [a-z_]+ \"\\\"\""
        rules["xid"] = (extraIds + NONE).joinToString(" | ") { GrammarSyntax.lit(it) }
        rules["xvalue"] = GrammarSyntax.string(nonEmpty = false)

        rules["quote"] = GrammarSyntax.string(nonEmpty = true)
        rules.putAll(GrammarSyntax.commonRules())

        return GrammarSyntax.render(rules)
    }

    /** The grammar rule that lists the ids a slot may take, for tests and diagnostics. */
    fun ruleNameOf(slot: SlotKey): String = when (slot.kind) {
        SlotKind.AMOUNT -> "amt"
        SlotKind.DATE -> "date"
        SlotKind.DEADLINE -> "due"
        SlotKind.IBAN -> "iban"
        SlotKind.REFERENCE -> "ref"
        SlotKind.REFERENCE_LIST -> "refs"
        SlotKind.NAME -> "name"
        SlotKind.ACTION -> "action"
    }

    private fun slotRule(slot: SlotKey): String = "${GrammarSyntax.key(slot.json)} ws ${ruleNameOf(slot)}"

    /** `"NONE"` or `{"id":<one of ids>,"r":<role>,"c":<conf>}`, and for deadlines also `{"rule":<quote>,"r":..,"c":..}`. */
    private fun valueRule(ids: List<String>, roleRule: String, allowRule: Boolean): String {
        val alternatives = mutableListOf(GrammarSyntax.lit(NONE))
        if (ids.isNotEmpty()) {
            alternatives += "\"{\" ws ${GrammarSyntax.key("id")} ws (${ids.joinToString(" | ") { GrammarSyntax.lit(it) }}) " +
                "\",\" ws ${GrammarSyntax.key("r")} ws $roleRule \",\" ws ${GrammarSyntax.key("c")} ws conf ws \"}\""
        }
        if (allowRule) {
            alternatives += GrammarSyntax.obj("rule" to "quote", "r" to roleRule, "c" to "conf")
        }
        return alternatives.joinToString(" | ")
    }

    /** `"NONE"` or `{"id":<one of ids>,"c":<conf>}`. */
    private fun idObject(ids: List<String>): String {
        if (ids.isEmpty()) return GrammarSyntax.lit(NONE)
        return "${GrammarSyntax.lit(NONE)} | \"{\" ws ${GrammarSyntax.key("id")} ws (${ids.joinToString(" | ") { GrammarSyntax.lit(it) }}) " +
            "\",\" ws ${GrammarSyntax.key("c")} ws conf ws \"}\""
    }

    private fun DocType.rule() = id.replace('_', '-')
}

/**
 * The GBNF for call 2, the free text. Plain: five string fields, no ids, no candidates. What the
 * model writes here is verified afterwards (quotes) or shown as AI-written (title, an unverifiable
 * summary).
 * ```
 * {"other":"","title":"...","subject":"...","summary":"...","qs":["...","...","..."]}
 * ```
 */
object TextGrammar {
    const val MAX_OTHER_CHARS = 40
    const val MAX_TITLE_CHARS = 60
    const val MAX_SUBJECT_CHARS = 100
    const val MAX_SUMMARY_CHARS = 240
    const val MAX_QUESTION_CHARS = 100
    const val MAX_QUESTIONS = 3

    fun build(): String {
        val rules = LinkedHashMap<String, String>()
        rules["root"] = listOf(
            "\"{\" ws ${GrammarSyntax.key("other")} ws other",
            "${GrammarSyntax.key("title")} ws title",
            "${GrammarSyntax.key("subject")} ws subject",
            "${GrammarSyntax.key("summary")} ws summary",
            "${GrammarSyntax.key("qs")} ws qs ws \"}\"",
        ).joinToString(" \",\" ws ")
        rules["other"] = GrammarSyntax.string(nonEmpty = false)
        rules["title"] = GrammarSyntax.string(nonEmpty = true)
        rules["subject"] = GrammarSyntax.string(nonEmpty = true)
        rules["summary"] = GrammarSyntax.string(nonEmpty = true)
        rules["question"] = GrammarSyntax.string(nonEmpty = true)
        rules["qs"] = "\"[\" ws question \",\" ws question \",\" ws question ws \"]\""
        rules.putAll(GrammarSyntax.commonRules())
        return GrammarSyntax.render(rules)
    }
}

/** GBNF building blocks shared by the two grammars. Nothing else in the domain writes GBNF. */
internal object GrammarSyntax {

    /** `{"k1":rule1,"k2":rule2,...}` as a GBNF sequence. */
    fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(" \",\" ws ", prefix = "\"{\" ws ", postfix = " ws \"}\"") { (k, rule) -> "${key(k)} ws $rule" }

    fun enumRule(values: List<String>): String = values.joinToString(" | ") { lit(it) }

    /** A JSON string, unbounded: `qchar+` or `qchar*`. Lengths are capped by the parser, not counted here. */
    fun string(nonEmpty: Boolean): String = "\"\\\"\" qchar${if (nonEmpty) "+" else "*"} \"\\\"\""

    /** A JSON key as a GBNF terminal: `"\"type\":"`. */
    fun key(name: String): String = "\"\\\"$name\\\":\""

    /** A quoted JSON string literal as a GBNF terminal: `"\"A1\""`. */
    fun lit(s: String): String = "\"\\\"$s\\\"\""

    /** The rules every grammar here ends with: what a character in a string may be, and optional whitespace. */
    fun commonRules(): Map<String, String> = linkedMapOf(
        "qchar" to "[^\"\\\\\\n\\r]",
        "ws" to "[ ]?",
    )

    fun render(rules: Map<String, String>): String = rules.entries.joinToString("\n") { (name, body) -> "$name ::= $body" }
}
