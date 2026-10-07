package com.postsaimanager.core.domain.extraction.zones

/**
 * How much text the letter has, as a neutral line of context for the model ("The letter holds 3 lines of text, 24 words."). It is a
 * fact about the page, never a rule: nothing here decides a type, a few lines of text do not make a message. It is there because a
 * model reading a screenshot of two appointment reminders and a model reading a four-page decision are shown the same instruction, and
 * the size of what they read is the one thing that tells them apart without any word of the letter.
 */
object LetterExtent {

    /** The context line for the [renderedText] the model is given, counting its non-blank lines and its words. */
    fun describe(renderedText: String): String {
        val lines = renderedText.lineSequence().count { it.isNotBlank() }
        val words = renderedText.split(WHITESPACE).count { it.isNotEmpty() }
        return "The letter holds $lines ${if (lines == 1) "line" else "lines"} of text, $words ${if (words == 1) "word" else "words"}."
    }

    private val WHITESPACE = Regex("\\s+")
}
