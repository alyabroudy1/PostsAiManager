package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Field
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Item
import com.postsaimanager.core.domain.timeline.EventKinds
import java.util.Locale

/**
 * The text the reader is given: what it is to do, the letter's lines with their ids, the candidates with theirs, and what each key and
 * each code of the answer means (taken from the registries, so a new meaning brings its own sentence). The answer's shape is the schema's;
 * the prompt explains its one-letter keys and its short codes once, and says what the fields are for.
 *
 * The instructions are English (the letter may be in any language) and say nothing about a country, a language or a kind of letter.
 */
object GemmaPrompt {

    /** A cap on the whole user text, in characters: the window also holds the picture, the schema's tokens and the answer. */
    const val MAX_CHARS = 9_000
    const val MIN_CHARS = 3_000

    /** What the reader is asked first when it starts with a summary: free text, written before anything else so it can be shown at once. */
    const val SUMMARY_ASK = "\nFIRST, before anything else, and in plain text (not JSON): write a short summary of this document, one or two sentences of at most " +
        "${GemmaTextWriter.MAX_SUMMARY_CHARS} characters, in the language the document is written in, saying what it is about and what it asks of its reader, if anything. " +
        "Use only what the letter says. Answer with the summary only.\n"

    /** The two messages of a reading that starts with a summary: [first] is the letter and the question for the summary, [second] the field guide. */
    class Turns(val first: String, val second: String)

    fun system(imageOnly: Boolean, summaryFirst: Boolean = false): String = buildString {
        if (summaryFirst) append("You read one letter. When you are asked for a summary, answer in plain text; after that you answer with JSON only. ")
        else append("You read one letter and answer with JSON only. ")
        append("The JSON uses the short keys and codes the field list below explains. ")
        if (imageOnly) {
            append("You see the letter as a picture and nothing else: copy names and values exactly as printed; leave a text empty when you cannot read it. ")
        } else {
            append("You see the letter as a picture and as numbered lines (L1, L2, ...) with the values found in them as numbered candidates. ")
            append("Answer with ids from the lists you are given; never write a value yourself. Use \"none\" when there is nobody or nothing for a question. ")
        }
        append("Decide what each thing means from the whole letter, in whatever language it is written. ")
        append("The sender is the party that wrote the letter, the addressee the party it is addressed to, the contact the person at the sender who handles the matter, ")
        append("the subject person someone the letter is about when that is not the addressee. A label such as a field name is never a name.")
    }

    fun user(letter: GemmaLetter, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT, forcedCategory: String? = null, maxChars: Int = MAX_CHARS): String {
        val (head, tail) = parts(letter, vocab, forcedCategory, maxChars)
        return head + tail
    }

    /**
     * The same text as two messages for one conversation: the letter and the question for a short plain-text summary first (so the model
     * has read the letter, and the picture, once, and the summary can be shown at once), then the field guide whose answer is the JSON.
     */
    fun turns(letter: GemmaLetter, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT, forcedCategory: String? = null, maxChars: Int = MAX_CHARS): Turns {
        val (head, tail) = parts(letter, vocab, forcedCategory, maxChars)
        return Turns(first = head + SUMMARY_ASK, second = "Now the details of the same letter.\n" + tail.trimStart('\n'))
    }

    private fun parts(letter: GemmaLetter, vocab: GemmaVocabulary, forcedCategory: String?, maxChars: Int): Pair<String, String> {
        val head = buildString {
            if (letter.isImageOnly) {
                append("Read the picture of the letter.\n")
            } else {
                append("LINES (id | zone | position on the page | text):\n")
                letter.lines.forEach { l ->
                    append(l.id).append(" | ").append(l.zone).append(" | ").append(position(l)).append(" | ").append(l.text)
                    if (l.tags.isNotEmpty()) append("  [").append(l.tags.joinToString(",")).append(']')
                    append('\n')
                }
                append("\nCANDIDATES (id | kind | printed | label near it | line):\n")
                letter.candidates.forEach { c ->
                    append(c.id).append(" | ").append(kindWord(c.kind)).append(" | ").append(c.raw).append(" | ").append(c.label).append(" | ")
                        .append(c.lineId.orEmpty()).append('\n')
                }
            }
        }
        val tail = buildString {
            append('\n').append(guide(letter.isImageOnly, vocab))
            forcedCategory?.let { append("\nThe user says this document is ").append(it).append(".\n") }
        }
        // The lines are what is cut when the text is too long: the instructions after them always stay.
        val room = (maxChars - tail.length).coerceAtLeast(MIN_HEAD_CHARS)
        return (if (head.length > room) head.take(room) + "\n" else head) to tail
    }

    private fun guide(imageOnly: Boolean, vocab: GemmaVocabulary): String = buildString {
        append("ANSWER: one JSON object with these keys, in this order. A list holds only what exists: an empty list is [] and no entry is ever written for nobody.\n")
        append("- ${Field.ASKS_READER}: ${GemmaVocabulary.YES} or ${GemmaVocabulary.NO}: does this document ask its reader to do anything at all? " +
            "It is ${GemmaVocabulary.YES} when the reader is asked to attend or be present at an appointment, bring something, pay, reply, send or sign. " +
            "A reminder of an appointment the reader must attend is ${GemmaVocabulary.YES}. " +
            "A document that only informs, such as proof of a payment already made, asks nothing: answer ${GemmaVocabulary.NO}, and then ${Field.ACTIONS} is empty.\n")
        append("- ${Field.PAID}: has what the document is about been paid already? Answer with one of:\n")
        PaidState.entries.forEach { append("    ").append(it.id).append(": ").append(it.sentence).append('\n') }
        append("  A payment that was made (a till slip, a receipt, a confirmation of payment, a debit already taken) is ${PaidState.ALREADY_PAID.id}, " +
            "and then there is nothing to pay: no pay action and no amount to pay.\n")
        append("- ${Field.CATEGORY}: the code of what the document is, decided with the two answers above in mind.\n")
        if (imageOnly) {
            append("- ${Field.PARTIES}: one entry per party that exists: {${Item.WHO}: who, ${Item.NAME}: the name as printed, ${Item.KIND}: its kind code}.\n")
            append("- ${Field.DATES}, ${Field.AMOUNTS}: entries {${Item.VALUE}: the value as printed (a date as yyyy-MM-dd, an amount as 1234.50 EUR), ${Item.MEANING}: the code of what it means}.\n")
            append("- ${Field.REFERENCES}: entries {${Item.VALUE}: each number as printed, ${Item.KIND}: the code of its kind (the account is the iban kind)}.\n")
        } else {
            append("- ${Field.PARTIES}: one entry per party that exists: {${Item.WHO}: who, ${Item.ID}: a name candidate id or a line id, ${Item.KIND}: its kind code}.\n")
            append("- ${Field.DATES}, ${Field.AMOUNTS}: entries {${Item.ID}: the candidate that matters, ${Item.MEANING}: the code of what it means}. " +
                "Take the meaning from the letter's own words next to the value (the label column of the candidates); a value the letter does " +
                "not describe, such as a line of a table, a unit price or a part of a total, means the code of \"none of these\". " +
                "Only one value can be the amount to pay and only one the date of the letter.\n")
            append("- ${Field.REFERENCES}: entries {${Item.ID}: a reference number or the account to pay to, ${Item.KIND}: the code of its kind}.\n")
        }
        append("- ${Field.ACTIONS}: what the letter asks of its reader, entries {${Item.KIND}: the action code" +
            (if (imageOnly) "" else ", ${Item.DATE_ID}: the date it is for, ${Item.AMOUNT_ID}: the amount it is for, or \"none\"") + "}. " +
            "The list may be empty, and it should be empty unless the letter itself asks the reader to do something; " +
            "a letter that only informs asks for nothing, and a date that is not a deadline or an appointment is not an action's date.\n")
        append("- ${Field.EVENT_KIND}: the code of what this document reports for the timeline of its matter; the code of \"none of these\" when no kind fits.\n")
        append("- ${Field.LANGUAGE}: the language the document is written in, as a short code.\n")
        append("- ${Field.NAME}: a short name of this document in its own language, at most ${GemmaSchema.MAX_NAME_CHARS} characters.\n")
        if (imageOnly) {
            append("- ${Field.SUMMARY}: one or two sentences in the letter's language: what it says and what the reader must do.\n")
            append("- ${Field.KEY_INFO}: up to ${GemmaSchema.MAX_KEY_INFO} other facts the reader needs, entries {${Item.LABEL}: a label of at most four words, ${Item.VALUE}: the value copied as printed}.\n")
        }
        append("\nWHO (${Item.WHO}):\n")
        GemmaSchema.PARTIES.forEach { append("- ").append(vocab.partyRoleCodes.codeOf(it)).append(": ").append(it).append('\n') }
        codes("KIND OF PARTY", vocab.partyKindCodes, vocab.partyKinds)
        append("DATE MEANINGS:\n")
        vocab.dateMeanings.forEach { append("- ").append(vocab.dateMeaningCodes.codeOf(it.id)).append(" = ").append(it.id).append(": ").append(it.description).append('\n') }
        append("- ").append(vocab.dateMeaningCodes.codeOf(GemmaVocabulary.OTHER)).append(": none of these\n")
        append("AMOUNT MEANINGS:\n")
        vocab.amountMeanings.forEach { append("- ").append(vocab.amountMeaningCodes.codeOf(it.id)).append(" = ").append(it.id).append(": ").append(it.description).append('\n') }
        append("- ").append(vocab.amountMeaningCodes.codeOf(GemmaVocabulary.OTHER)).append(": none of these\n")
        append("KIND OF REFERENCE:\n")
        vocab.referenceKinds.forEach { append("- ").append(vocab.referenceKindCodes.codeOf(it)).append(" = ").append(it).append('\n') }
        append("ACTIONS:\n")
        vocab.actionKinds.forEach { append("- ").append(vocab.actionKindCodes.codeOf(it.id)).append(" = ").append(it.id).append(": ").append(it.task).append('\n') }
        append("CATEGORIES:\n")
        vocab.categories.forEach { append("- ").append(vocab.categoryCodes.codeOf(it.id)).append(" = ").append(it.id).append(": ").append(it.promptLine).append('\n') }
        append("- ").append(vocab.categoryCodes.codeOf(GemmaVocabulary.DOCUMENT_CATEGORY)).append(": none of these\n")
        append("EVENT KINDS (the letter ...):\n")
        vocab.eventKinds.scored.forEach { append("- ").append(vocab.eventKindCodes.codeOf(it.id)).append(" = ").append(it.id).append(": ").append(it.description).append('\n') }
        append("- ").append(vocab.eventKindCodes.codeOf(EventKinds.INFORMATION)).append(": none of these\n")
    }

    /** One list of codes with the word each stands for, under [title]. */
    private fun StringBuilder.codes(title: String, book: CodeBook, ids: List<String>) {
        append(title).append(":\n")
        ids.forEach { append("- ").append(book.codeOf(it)).append(" = ").append(it).append('\n') }
    }

    private fun position(l: GemmaLine) = String.format(Locale.ROOT, "p%d x%.2f y%.2f", l.page, l.x, l.y)

    private fun kindWord(kind: CandidateKind) = kind.name.lowercase()

    private const val MIN_HEAD_CHARS = 2_000
}
