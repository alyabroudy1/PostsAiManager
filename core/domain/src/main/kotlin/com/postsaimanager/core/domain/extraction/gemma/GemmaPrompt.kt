package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Field
import java.util.Locale

/**
 * The text the reader is given: what it is to do, the letter's lines with their ids, the candidates with theirs, and what each word of
 * the answer's lists means (taken from the registries, so a new meaning brings its own sentence). The answer's shape is the schema's,
 * not described here; the prompt only says what the fields are for.
 *
 * The instructions are English (the letter may be in any language) and say nothing about a country, a language or a kind of letter.
 */
object GemmaPrompt {

    /** A cap on the whole user text, in characters: the window also holds the picture, the schema's tokens and the answer. */
    const val MAX_CHARS = 9_000
    const val MIN_CHARS = 3_000

    fun system(imageOnly: Boolean): String = buildString {
        append("You read one letter and answer with JSON only. ")
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
        return (if (head.length > room) head.take(room) + "\n" else head) + tail
    }

    private fun guide(imageOnly: Boolean, vocab: GemmaVocabulary): String = buildString {
        append("FIELDS:\n")
        if (imageOnly) {
            append("- sender, addressee, contact, subjectPerson: the name as printed, with its kind (person, authority, company, other); empty name when there is none.\n")
            append("- dates, amounts: each value as printed (a date as yyyy-MM-dd, an amount as 1234.50 EUR) with what it means.\n")
            append("- references: each number as printed with its kind (iban, or the kind of number it is).\n")
        } else {
            append("- ${Field.SENDER}, ${Field.ADDRESSEE}, ${Field.CONTACT}, ${Field.SUBJECT_PERSON}: a name candidate id or a line id, with its kind (person, authority, company, other).\n")
            append("- ${Field.DATES}, ${Field.AMOUNTS}: the candidates that matter, each with what it means.\n")
            append("- ${Field.REFERENCES}: reference numbers and the account to pay to, each with its kind.\n")
        }
        append("- ${Field.ACTIONS}: what the letter asks of its reader, with the date and the amount it is for. " +
            "The list may be empty, and it should be empty unless the letter itself asks the reader to do something; " +
            "a letter that only informs asks for nothing, and a date that is not a deadline or an appointment is not an action's date.\n")
        append("- ${Field.CATEGORY}: what the document is.\n")
        append("- ${Field.LANGUAGE}: the language the document is written in, as a short code.\n")
        append("- ${Field.NAME}: a short name of this document in its own language, at most ${GemmaSchema.MAX_NAME_CHARS} characters.\n")
        append("- ${Field.SUMMARY}: one or two sentences in the letter's language: what it says and what the reader must do.\n")
        append("- ${Field.KEY_INFO}: up to ${GemmaSchema.MAX_KEY_INFO} other facts the reader needs, a label of at most four words and the value copied as printed.\n")
        append("\nDATE MEANINGS:\n")
        vocab.dateMeanings.forEach { append("- ").append(it.id).append(": ").append(it.description).append('\n') }
        append("- ").append(GemmaVocabulary.OTHER).append(": none of these\n")
        append("AMOUNT MEANINGS:\n")
        vocab.amountMeanings.forEach { append("- ").append(it.id).append(": ").append(it.description).append('\n') }
        append("- ").append(GemmaVocabulary.OTHER).append(": none of these\n")
        append("ACTION KINDS:\n")
        vocab.actionKinds.forEach { append("- ").append(it.id).append(": ").append(it.task).append('\n') }
        append("CATEGORIES:\n")
        vocab.categories.forEach { append("- ").append(it.id).append(": ").append(it.phrase).append('\n') }
        append("- ").append(GemmaVocabulary.DOCUMENT_CATEGORY).append(": none of these\n")
    }

    private fun position(l: GemmaLine) = String.format(Locale.ROOT, "p%d x%.2f y%.2f", l.page, l.x, l.y)

    private fun kindWord(kind: CandidateKind) = kind.name.lowercase()

    private const val MIN_HEAD_CHARS = 2_000
}
