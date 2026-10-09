package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.text.SummaryLimits
import com.postsaimanager.core.domain.extraction.v2.PartyKind

/** The labels of the "Questions" reader's one answer, in the order they are asked: the protocol between the prompt and [QuestionAnswerParser]. */
enum class QaLabel {
    /** The first line of the answer when a summary is wanted: it is handed on the moment its line ends ([SummaryLineWatcher]). */
    SUMMARY,
    SENDER,
    RECIPIENT,
    CONTACT,

    /** `yes | no`, the paid state, then one `kind — by when` per thing asked (the paid state used to be a line of its own). */
    ASKS,

    /** The date the letter was written (asked on a line of its own so that it is never left out), or none. */
    LETTERDATE,
    DATES,
    AMOUNTS,

    /** `amount — by when`: the one amount the reader has to pay, apart from the list of amounts; the pay action takes its amount from it. */
    TOPAY,
    REFERENCES,
    TYPE,
    TITLE,
    EVENT,

    /** Not asked in the short answer; still read when a model gives one (the paid state is part of [ASKS]). */
    PAID,

    /** The last line: a two-letter code. */
    LANGUAGE,
}

/**
 * The text of the "Questions" reader style: the letter as plain lines, the way the chat gets it (no ids, no candidate table, no JSON
 * schema), then every question once, in one message, answered in one labelled line each. The lists a label chooses from (what a date
 * means, what an action is, the categories) come from the registries, so a new entry is one line in its own registry and nothing here.
 *
 * The answer is short on purpose: decoding is the slow part of a reading, so the lines are terse (no sentences) and the lists are
 * capped. With a summary wanted, it is the first line of the same answer, not a turn of its own.
 *
 * The instructions are English (the letter may be in any language) and say nothing about a country, a language or a kind of letter.
 */
object QuestionPrompt {

    const val NONE_WORD = GemmaVocabulary.NONE

    /** The most items a list line (dates, amounts, references) has: the rest of a long letter is not asked for. */
    const val MAX_ITEMS = 5

    /** The most things the letter is said to ask: the main ones only (a reader of a letter does not need every possible step). */
    const val MAX_ASKS = 2

    /**
     * The labels the answer holds: [QaLabel.SUMMARY] only when a summary is wanted. The paid state has a line of its own again (it was a part
     * of ASKS, where a model took its word, such as to_pay, for the action kind); the builder still understands it inside ASKS.
     */
    fun asked(withSummary: Boolean): List<QaLabel> = QaLabel.entries.filter { it != QaLabel.SUMMARY || withSummary }

    fun system(): String =
        "You read one letter and answer questions about it. " +
            "Answer in exactly the format requested: one terse line per label, nothing else, no full sentences except where a sentence is asked for. " +
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

    /** The one message: the letter, then the questions ([questions]); with [withSummary] the answer starts with the summary line. */
    fun message(letterText: String, withSummary: Boolean, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT, forcedCategory: String? = null): String =
        letterText + "\n" + questions(vocab, forcedCategory, withSummary)

    fun questions(vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT, forcedCategory: String? = null, withSummary: Boolean = false): String = buildString {
        append("Answer now: one line per label (the label in capitals, a colon, a terse answer), nothing else. Write names and values as printed in the letter. ")
        append("When something is not in the letter, write exactly: $NONE_WORD. Separate items with \";\", at most $MAX_ITEMS per list.\n")
        if (withSummary) {
            append("${QaLabel.SUMMARY}: first line, one sentence (at most ${SummaryLimits.MAX_CHARS} characters) in the language of the letter: ")
            append("what it is, from whom, and the main fact; mention a request only if there is one\n")
        }
        val kinds = PartyKind.entries.joinToString(", ") { it.name.lowercase() }
        append("${QaLabel.SENDER}: who sent or issued it (organisation, shop or person): its full name exactly as printed (every word of the name, with its title or legal form, never shortened; ")
        append("a name may continue on the next line) | its kind: one of $kinds\n")
        append("${QaLabel.RECIPIENT}: to whom it is addressed (the name only; $NONE_WORD if no one is named) | its kind\n")
        append("${QaLabel.CONTACT}: a person at the sender the reader can contact (a person's name only, never a phone number or an e-mail address; $NONE_WORD if no person is named) ")
        append("| their phone number | their e-mail\n")
        append("${QaLabel.ASKS}: ${GemmaVocabulary.YES} or ${GemmaVocabulary.NO} (does it ask the reader to do anything?); then at most $MAX_ASKS separate things the reader must do, the main one first, each as: kind — by when (a date). ")
        append("Something to bring to an appointment is part of attending it; cancelling or objecting comes after the main one. kind is exactly one of: ")
        append(vocab.actionKinds.joinToString("; ") { "${it.id} (${it.task})" }).append('\n')
        append("${QaLabel.PAID}: the paid state, one of: ")
        append(PaidState.entries.joinToString("; ") { "${it.id} (${it.sentence})" }).append('\n')
        append("${QaLabel.LETTERDATE}: the date the letter was written, as printed ($NONE_WORD if it has none)\n")
        append("${QaLabel.DATES}: the important dates, each as: date — meaning. meaning is one of: ")
        append(vocab.dateMeanings.joinToString("; ") { "${it.id} (${it.description})" }).append("; ${GemmaVocabulary.OTHER}\n")
        append("${QaLabel.AMOUNTS}: the important amounts, each as: amount — meaning. meaning is one of: ")
        append(vocab.listedAmountMeanings.joinToString("; ") { "${it.id} (${it.description})" }).append("; ${GemmaVocabulary.OTHER}\n")
        append("${QaLabel.TOPAY}: the amount the reader has to pay (not a total that includes what was already paid), as: amount — by when ($NONE_WORD if nothing is to be paid)\n")
        append("${QaLabel.REFERENCES}: the reference numbers (customer, case, invoice, account ...), each as: number — kind. kind is one of: ")
        append(vocab.referenceKinds.joinToString(", ")).append('\n')
        append("${QaLabel.TYPE}: what kind of document this is, one of: ")
        append(vocab.categories.joinToString("; ") { "${it.id} (${it.promptLine})" }).append("; ${GemmaVocabulary.DOCUMENT_CATEGORY} (fits none of these)\n")
        append("${QaLabel.TITLE}: a short name for this document, at most 6 words\n")
        append("${QaLabel.EVENT}: what the letter reports, one word, one of: ")
        append(vocab.eventKindIds.joinToString(", ")).append('\n')
        append("${QaLabel.LANGUAGE}: the language the letter is written in, a two-letter code\n")
        forcedCategory?.let { append("The user says this document is ").append(it).append(".\n") }
    }
}
