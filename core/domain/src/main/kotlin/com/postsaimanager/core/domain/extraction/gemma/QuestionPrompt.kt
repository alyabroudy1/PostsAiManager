package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.v2.PartyKind

/** The labels of the "Questions" reader's one answer, in the order they are asked: the protocol between the prompt and [QuestionAnswerParser]. */
enum class QaLabel {
    SENDER,
    RECIPIENT,
    CONTACT,
    ASKS,
    DATES,
    AMOUNTS,
    REFERENCES,
    TYPE,
    TITLE,
    LANGUAGE,
    PAID,
    EVENT,
}

/**
 * The text of the "Questions" reader style: the letter as plain lines, the way the chat gets it (no ids, no candidate table, no JSON
 * schema), then every question once, in one message, answered in one labelled line each. The lists a label chooses from (what a date
 * means, what an action is, the categories) come from the registries, so a new entry is one line in its own registry and nothing here.
 *
 * The instructions are English (the letter may be in any language) and say nothing about a country, a language or a kind of letter.
 */
object QuestionPrompt {

    const val NONE_WORD = GemmaVocabulary.NONE

    fun system(): String =
        "You read one letter and answer questions about it. When you are asked for a summary, answer in plain text. " +
            "When you are asked the questions, answer in exactly the format requested: one line per label, nothing else. " +
            "Use only what the letter says and write names and values as they are printed in it."

    /** The letter as the chat sees it: its lines in reading order, a page marker where a page starts, cut to [maxChars]. */
    fun letterText(letter: GemmaLetter, maxChars: Int): String {
        val text = buildString {
            append("LETTER:\n")
            var page = 0
            letter.lines.forEach { l ->
                if (l.page != page) {
                    page = l.page
                    if (page > 1) append("[page ").append(page).append("]\n")
                }
                append(l.text).append('\n')
            }
        }
        return if (text.length > maxChars) text.take(maxChars) + "\n" else text
    }

    /** The first message: the letter and the question for a short plain-text summary, which is shown at once. */
    fun summaryTurn(letterText: String): String = letterText + GemmaPrompt.SUMMARY_ASK

    fun questions(vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT, forcedCategory: String? = null): String = buildString {
        append("Now answer these questions about the same letter, all at once, in exactly this format: one line per label, the label in capitals, a colon, ")
        append("then the answer, and nothing else. Write names and values as they are printed in the letter. ")
        append("Write $NONE_WORD where nothing applies. Separate several items with \";\".\n")
        val kinds = PartyKind.entries.joinToString(", ") { it.name.lowercase() }
        append("${QaLabel.SENDER}: who sent the letter (the name only) | its kind: one of $kinds\n")
        append("${QaLabel.RECIPIENT}: to whom the letter is addressed (the name only) | its kind\n")
        append("${QaLabel.CONTACT}: the contact person named at the sender (the name only) | their phone number | their e-mail\n")
        append("${QaLabel.ASKS}: does the letter ask the reader to do anything? ${GemmaVocabulary.YES} or ${GemmaVocabulary.NO}; if yes, then for each thing: ")
        append("${GemmaVocabulary.YES} — kind — by when (a date). kind is one of: ")
        append(vocab.actionKinds.joinToString("; ") { "${it.id} (${it.task})" }).append('\n')
        append("${QaLabel.DATES}: the important dates, each as: date — meaning. meaning is one of: ")
        append(vocab.dateMeanings.joinToString("; ") { "${it.id} (${it.description})" }).append("; ${GemmaVocabulary.OTHER}\n")
        append("${QaLabel.AMOUNTS}: the important amounts, each as: amount — meaning. meaning is one of: ")
        append(vocab.amountMeanings.joinToString("; ") { "${it.id} (${it.description})" }).append("; ${GemmaVocabulary.OTHER}\n")
        append("${QaLabel.REFERENCES}: the reference numbers (customer, case, invoice, account ...), each as: number — kind. kind is one of: ")
        append(vocab.referenceKinds.joinToString(", ")).append('\n')
        append("${QaLabel.TYPE}: what kind of document this is, one of: ")
        append(vocab.categories.joinToString("; ") { "${it.id} (${it.promptLine})" }).append("; ${GemmaVocabulary.DOCUMENT_CATEGORY} (fits none of these)\n")
        append("${QaLabel.TITLE}: a short name for this document, at most 6 words\n")
        append("${QaLabel.LANGUAGE}: the language the letter is written in, as a two-letter code\n")
        append("${QaLabel.PAID}: has what the letter is about been paid already? One of: ")
        append(PaidState.entries.joinToString("; ") { "${it.id} (${it.sentence})" }).append('\n')
        append("${QaLabel.EVENT}: what the letter reports, one of: ")
        append(vocab.eventKindIds.joinToString(", ")).append('\n')
        forcedCategory?.let { append("The user says this document is ").append(it).append(".\n") }
    }
}
