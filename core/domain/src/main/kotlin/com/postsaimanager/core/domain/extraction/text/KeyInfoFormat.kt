package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.GrammarSyntax

/**
 * The shape of the open "key information" answer, and the one place that knows it: the grammar that forces it and the parser that reads it.
 *
 * ```
 * Zählernummer: 1EMH0012345678
 * Vertragsbeginn: 01.01.2027
 * ```
 * One fact per line, `label: value`, or the single word NONE. The label is the model's own words in the document's language (it cannot
 * contain a colon), the value is a line of the document. Both lengths are bounded in the grammar itself (nested optionals, no `{m,n}`), so
 * the answer cannot run long whatever the model leans toward: the worst case is [MAX_FACTS] lines of [MAX_LABEL_CHARS] + 2 + [MAX_VALUE_CHARS]
 * characters, and [MAX_TOKENS] ends the decode first for any ordinary text.
 */
object KeyInfoFormat {

    /** How many facts one answer may list: the most the verifier keeps as extras. */
    const val MAX_FACTS = 6
    const val MAX_LABEL_CHARS = 40
    const val MAX_VALUE_CHARS = 80

    /** The decode budget of the one generation: about 30 tokens a fact. */
    const val MAX_TOKENS = 200

    /** The answer when the document has nothing more a person would need. */
    const val NONE = "NONE"

    /** A fact the model wrote, not yet checked. */
    data class Fact(val label: String, val value: String)

    /** `NONE | fact ("\n" fact)*` with at most [MAX_FACTS] facts, each with a bounded label and value. */
    fun grammar(): String {
        val rules = linkedMapOf(
            "root" to "\"$NONE\" | ${GrammarSyntax.list("fact", MAX_FACTS, separator = "nl")}",
            "fact" to "label \": \" value",
            "label" to bounded("lchar", MAX_LABEL_CHARS),
            "value" to bounded("vchar", MAX_VALUE_CHARS),
            "lchar" to "[^:\\n\\r\"\\\\]",
            "vchar" to "[^\\n\\r]",
            "nl" to "\"\\n\"",
        )
        return GrammarSyntax.render(rules)
    }

    /** One to [max] of [char], as nested optionals: `c (c (c)?)?`. */
    private fun bounded(char: String, max: Int): String {
        var tail = ""
        repeat(max - 1) { tail = " ($char$tail)?" }
        return char + tail
    }

    /** The facts of an answer, in order; empty for NONE or an answer with no `label: value` line. */
    fun parse(answer: String): List<Fact> {
        val text = answer.trim()
        if (text.isEmpty() || text == NONE) return emptyList()
        return text.lines().mapNotNull { line ->
            val at = line.indexOf(':')
            if (at <= 0) return@mapNotNull null
            val label = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            if (label.isEmpty()) null else Fact(label, value)
        }.take(MAX_FACTS)
    }
}
