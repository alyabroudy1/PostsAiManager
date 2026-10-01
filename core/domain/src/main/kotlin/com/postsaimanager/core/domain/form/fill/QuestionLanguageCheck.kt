package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.model.OcrBlock

/**
 * A cheap, data-free check that a question the (small) model wrote is in the form's language and is about the field asked for.
 * It compares the question only with the field's printed label and with the words the form itself prints: no word list, no
 * language names. A line that fails is not shown; the template question in the form's language is used instead.
 *
 * - the question contains the label as printed (what the model was told to keep),
 * - the question and the label are written in the same script,
 * - for a form that is not in English, most of the question's short words (the function words: "der", "das", "und", or "the", "of",
 *   "what" in the wrong language) are words the form prints too. Every form prints its language's function words many times, and an
 *   English line about a German form shares almost none of them. The label and the person's name are not counted; long words are not
 *   either (a good question uses words the form never prints). English forms skip this last test: the model's default is English.
 *
 * [formLanguage] is the document's stored language (from the extraction's second stage), the primary signal of the form's language.
 */
object QuestionLanguageCheck {

    /** At least this share of the question's short words must be words of the form. */
    private const val MIN_SHARED = 0.34

    private const val LONG_LABEL = 4
    private const val MIN_WORD = 3
    private const val SHORT_WORD = 4
    private const val ENGLISH = "en"

    /** The words (lower case, at least [MIN_WORD] letters) the form prints. */
    fun vocabularyOf(pages: List<List<OcrBlock>>): Set<String> =
        pages.flatten().flatMapTo(HashSet()) { words(it.text) }

    /** [ignored] is text whose words say nothing about the language (the person's name the question is for). */
    fun accepts(question: String, label: String, formLanguage: String?, vocabulary: Set<String>, ignored: String? = null): Boolean {
        if (!namesLabel(question, label)) return false
        if (dominantScript(question) != dominantScript(label)) return false
        val language = formLanguage?.substringBefore('-')?.substringBefore('_')?.lowercase()
        if (language.isNullOrBlank() || language == ENGLISH || vocabulary.isEmpty()) return true
        val short = (words(question) - words(label) - words(ignored.orEmpty())).filter { it.length <= SHORT_WORD }
        if (short.isEmpty()) return true
        return short.count { it in vocabulary }.toDouble() / short.size >= MIN_SHARED
    }

    /**
     * The question carries the label as printed. A label that is itself a sentence ("Hat Ihr Kind das Seepferdchen bereits?") is
     * rarely repeated whole, so for a label of [LONG_LABEL] words or more half of its words are enough.
     */
    private fun namesLabel(question: String, label: String): Boolean {
        val wanted = squash(label)
        if (wanted.isEmpty()) return false
        if (squash(question).contains(wanted)) return true
        val labelWords = words(label)
        if (labelWords.size < LONG_LABEL) return false
        val asked = words(question)
        return labelWords.count { it in asked } * 2 >= labelWords.size
    }

    /** Letters and digits only, lower case, single spaces: the label "as printed" survives punctuation and spacing differences. */
    private fun squash(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    private fun words(text: String): Set<String> =
        squash(text).split(' ').filter { w -> w.length >= MIN_WORD && w.any(Char::isLetter) }.toSet()

    private fun dominantScript(text: String): Character.UnicodeScript? =
        text.filter(Char::isLetter).groupingBy { Character.UnicodeScript.of(it.code) }.eachCount().maxByOrNull { it.value }?.key
}
