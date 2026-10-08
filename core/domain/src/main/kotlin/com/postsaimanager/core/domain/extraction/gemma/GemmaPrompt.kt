package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.text.SummaryLimits
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Field
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Item
import com.postsaimanager.core.domain.timeline.EventKinds

/**
 * The text the reader is given: what it is to do, the letter's lines with their ids, the candidates with theirs, and what each key and
 * each code of the answer means (taken from the registries, so a new meaning brings its own sentence). The answer's shape is the schema's;
 * the prompt explains its one-letter keys and its short codes once, and says what the fields are for.
 *
 * It is short on purpose (the phone reads it at 60 to 85 tokens a second): a line carries no position (the picture shows the layout),
 * its zone is written once for the lines that share it, a candidate's printed text is not repeated when it is the whole line it sits on
 * (or an earlier candidate's), each code is explained by one phrase, and the amount to pay is one field, not a claim on every amount.
 *
 * The instructions are English (the letter may be in any language) and say nothing about a country, a language or a kind of letter.
 */
object GemmaPrompt {

    /** A cap on the whole user text, in characters: the window also holds the picture, the schema's tokens and the answer. */
    const val MAX_CHARS = 9_000
    const val MIN_CHARS = 3_000

    /** What the reader is asked first when it starts with a summary: free text, written before anything else so it can be shown at once. */
    const val SUMMARY_ASK = "\nFIRST, before anything else, and in plain text (not JSON): write a short summary of this document, one or two sentences of at most " +
        "${SummaryLimits.MAX_CHARS} characters, in the language the document is written in, saying what it is about and what it asks of its reader, if anything. " +
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
                append("LINES (id text; a [zone] line says where the next lines are on the page, a [page] line starts a page):\n")
                var page = 0
                var zone = ""
                letter.lines.forEach { l ->
                    if (l.page != page) {
                        page = l.page
                        zone = ""
                        if (page > 1) append("[page ").append(page).append("]\n")
                    }
                    if (l.zone != zone) {
                        zone = l.zone
                        append('[').append(zone).append("]\n")
                    }
                    append(l.id).append(' ').append(l.text)
                    if (l.tags.isNotEmpty()) append("  [").append(l.tags.joinToString(",")).append(']')
                    append('\n')
                }
                append("\nCANDIDATES (id | kind | printed | label near it | line; = is the whole line, =id is the same as that candidate):\n")
                val seen = HashMap<String, String>()
                letter.candidates.forEach { c ->
                    val earlier = seen.putIfAbsent("${c.kind}:${c.raw}", c.id)
                    val whole = c.lineId?.let(letter::line)?.text?.trim() == c.raw
                    val printed = when {
                        earlier != null -> "=$earlier"
                        whole -> "="
                        else -> c.raw
                    }
                    append(c.id).append(" | ").append(kindWord(c.kind)).append(" | ").append(printed).append(" | ").append(c.label).append(" | ")
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
        append("ANSWER: one JSON object, these keys in this order. A list holds only what exists: [] when nothing, never an entry for nobody.\n")
        append("- ${Field.ASKS_READER}: ${GemmaVocabulary.YES} or ${GemmaVocabulary.NO}: does the document ask its reader to do anything? " +
            "${GemmaVocabulary.YES} when the reader is asked to attend or be present at an appointment, bring something, pay, reply, send or sign (a reminder of an appointment to attend too). " +
            "A document that only informs or proves a payment already made asks nothing: ${GemmaVocabulary.NO}, and ${Field.ACTIONS} is then empty.\n")
        append("- ${Field.PAID}: has what the document is about been paid already? One of:\n")
        PaidState.entries.forEach { append("    ").append(it.id).append(": ").append(it.sentence).append('\n') }
        append("  A till slip, a receipt or a debit already taken is ${PaidState.ALREADY_PAID.id}: nothing to pay.\n")
        append("- ${Field.CATEGORY}: the code of what the document is.\n")
        if (imageOnly) {
            append("- ${Field.PARTIES}: one entry per party that exists: {${Item.WHO}: who, ${Item.NAME}: the name as printed, ${Item.KIND}: its kind code}.\n")
            append("- ${Field.DATES}, ${Field.AMOUNTS}: entries {${Item.VALUE}: the value as printed (a date as yyyy-MM-dd, an amount as 1234.50 EUR), ${Item.MEANING}: the code of what it means}.\n")
            append("- ${Field.REFERENCES}: entries {${Item.VALUE}: each number as printed, ${Item.KIND}: the code of its kind (the account is the iban kind)}.\n")
        } else {
            append("- ${Field.PARTIES}: one entry per party that exists: {${Item.WHO}: who, ${Item.ID}: a name candidate id or a line id, ${Item.KIND}: its kind code}.\n")
            append("- ${Field.DATES}, ${Field.AMOUNTS}: entries {${Item.ID}: the candidate, ${Item.MEANING}: the code of what it means}. " +
                "Take the meaning from the letter's own words next to the value (the label); a value the letter does not describe, such as a line of a table, " +
                "a unit price or a part of a total, is the \"none of these\" code. Only one date can be the date of the letter.\n")
            append("- ${Field.TO_PAY}: the one amount candidate the reader has to pay, or \"${GemmaVocabulary.NONE}\" when the letter asks for no payment.\n")
            append("- ${Field.REFERENCES}: entries {${Item.ID}: a reference number or the account to pay to, ${Item.KIND}: the code of its kind}.\n")
        }
        append("- ${Field.ACTIONS}: what the letter asks of its reader, entries {${Item.KIND}: the action code" +
            (if (imageOnly) "" else ", ${Item.DATE_ID}: its date, ${Item.AMOUNT_ID}: its amount, or \"none\"") + "}. " +
            "Empty unless the letter itself asks the reader to do something; a date that is no deadline or appointment is no action's date.\n")
        append("- ${Field.EVENT_KIND}: the code of what the document reports for the timeline of its matter.\n")
        append("- ${Field.LANGUAGE}: the language the document is written in, as a short code.\n")
        append("- ${Field.NAME}: a short name of this document in its own language, at most ${GemmaSchema.MAX_NAME_CHARS} characters.\n")
        if (imageOnly) {
            append("- ${Field.SUMMARY}: one or two sentences in the letter's language: what it says and what the reader must do.\n")
            append("- ${Field.KEY_INFO}: up to ${GemmaSchema.MAX_KEY_INFO} other facts the reader needs, entries {${Item.LABEL}: a label of at most four words, ${Item.VALUE}: the value copied as printed}.\n")
        }
        append("\n")
        codes("WHO (${Item.WHO})", vocab.partyRoleCodes, GemmaSchema.PARTIES)
        codes("KIND OF PARTY", vocab.partyKindCodes, vocab.partyKinds)
        append("DATE MEANINGS:\n")
        vocab.dateMeanings.forEach { append("- ").append(vocab.dateMeaningCodes.codeOf(it.id)).append(": ").append(it.description).append('\n') }
        append("- ").append(vocab.dateMeaningCodes.codeOf(GemmaVocabulary.OTHER)).append(": none of these\n")
        append("AMOUNT MEANINGS:\n")
        (if (imageOnly) vocab.amountMeanings else vocab.listedAmountMeanings).forEach {
            append("- ").append(vocab.amountMeaningCodes.codeOf(it.id)).append(": ").append(it.description).append('\n')
        }
        append("- ").append(vocab.amountMeaningCodes.codeOf(GemmaVocabulary.OTHER)).append(": none of these\n")
        codes("KIND OF REFERENCE", vocab.referenceKindCodes, vocab.referenceKinds)
        append("ACTIONS:\n")
        vocab.actionKinds.forEach { append("- ").append(vocab.actionKindCodes.codeOf(it.id)).append(": ").append(it.task).append('\n') }
        append("CATEGORIES:\n")
        vocab.categories.forEach { append("- ").append(vocab.categoryCodes.codeOf(it.id)).append(": ").append(it.promptLine).append('\n') }
        append("- ").append(vocab.categoryCodes.codeOf(GemmaVocabulary.DOCUMENT_CATEGORY)).append(": none of these\n")
        append("EVENT KINDS (the letter ...):\n")
        vocab.eventKinds.scored.forEach { append("- ").append(vocab.eventKindCodes.codeOf(it.id)).append(": ").append(it.description).append('\n') }
        append("- ").append(vocab.eventKindCodes.codeOf(EventKinds.INFORMATION)).append(": none of these\n")
    }

    /** One list of codes with the word each stands for, on one line under [title] (a word needs no sentence). */
    private fun StringBuilder.codes(title: String, book: CodeBook, ids: List<String>) {
        append(title).append(": ").append(ids.joinToString(", ") { "${book.codeOf(it)} = $it" }).append('\n')
    }

    private fun kindWord(kind: CandidateKind) = kind.name.lowercase()

    private const val MIN_HEAD_CHARS = 2_000
}
