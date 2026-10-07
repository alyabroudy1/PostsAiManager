package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.GrammarSyntax

/**
 * The shape of the specific name a document gets, and the one place that knows it: the grammar that forces it and the cleaning of the
 * answer.
 *
 * ```
 * Kfz-Versicherung – Beitragsrechnung 2027
 * ```
 * One line of at most [MAX_CHARS] characters, no quotes and no line break. The length is bounded in the grammar itself (nested optionals, no
 * `{m,n}`), so the answer cannot run long whatever the model leans toward; [MAX_TOKENS] ends the decode first for any ordinary text.
 */
object DocumentNameFormat {

    /** The longest name: short enough for a list row's title. */
    const val MAX_CHARS = 60

    /** The decode budget of the one generation: a name of this length is about 20 tokens, in any script. */
    const val MAX_TOKENS = 48

    /** One to [MAX_CHARS] characters other than a quote, a backslash or a line break. */
    fun grammar(): String {
        val rules = linkedMapOf(
            "root" to GrammarSyntax.list("nchar", MAX_CHARS, separator = ""),
            "nchar" to "[^\"\\\\\\n\\r]",
        )
        return GrammarSyntax.render(rules)
    }

    /** The answer as one clean line: trimmed, inner whitespace collapsed to single spaces, wrapping quotes removed. Empty when there is none. */
    fun clean(answer: String): String =
        answer.trim().trim('"', '“', '”', '«', '»').trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
