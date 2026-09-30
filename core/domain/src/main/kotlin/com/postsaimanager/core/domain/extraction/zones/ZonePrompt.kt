package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.Question
import com.postsaimanager.core.domain.extraction.v2.QuestionGrammars
import com.postsaimanager.core.domain.extraction.v2.SelectionPrompt

/**
 * The text of the zone-by-zone reading. Pure text and data. English on purpose (the letter may be in any
 * language); roles are described by what a party does in the letter, never by the words a language uses.
 */
object ZonePrompt {

    private const val RULES = """You read one scanned letter one part (zone) at a time and answer questions about it. The letter can be in any language.

INPUT. Each question brings the text of the ZONES it is about, a HINT for each zone that says what such a block usually is, and the CANDIDATES found in those zones: values a program found, each with an id and the text as printed. A hint is only a prior. If the text says otherwise, follow the text.

ROLES are decided by what a party does in the letter, in any language: the SENDER wrote and sent it, the ADDRESSEE is who it is addressed to, the SUBJECT PERSON is who it is about, a CONTACT person handles the matter.

ANSWERS. Every answer is short and has exactly the shape the question asks for. Choose ids from the candidates shown with the question. Never write a date, an amount or a number yourself. Write NONE when the zones have no such value. Decide from what the text says, not from the order of the candidates. Confidence is LOW, MEDIUM or HIGH. A party's kind is PERSON, AUTHORITY, COMPANY or OTHER. Write a party's name as the letter prints it, without a form of address or a title. The sender and the addressee are never the same party."""

    /** The session's instruction: the rules and the layout class the page was matched to. */
    fun system(template: LayoutTemplate): String =
        RULES + "\n\nLAYOUT: ${template.id}, ${template.description}."

    /** The scoring session's instruction: judge one value at a time, answer Yes or No. */
    fun scoringSystem(template: LayoutTemplate): String =
        "You read one scanned letter one part (zone) at a time. The letter can be in any language. For each value you are shown, " +
            "answer Yes if the value is what the question says, otherwise No. A hint says what a block usually is; it is only a prior. " +
            "Decide from what the text says. Answer with the single word Yes or No.\n\nLAYOUT: ${template.id}, ${template.description}."

    /**
     * The instruction of the writing session that follows the scoring (the language, the names of the extras, the title, the summary,
     * the suggested questions). The scoring instruction says to answer Yes or No, and a small model obeys it over any question: measured
     * on the device, every written answer was "Yes". So what is written is asked under this one, which only says to write what the
     * question asks, in its format.
     */
    fun writingSystem(template: LayoutTemplate): String =
        "You read one scanned letter and answer questions about it, one at a time. The letter can be in any language. " +
            "Each question tells you what to write and the format of the answer: write exactly that, from what the letter says, " +
            "and nothing else.\n\nLAYOUT: ${template.id}, ${template.description}."

    /** The user turn of the body session: what the header established, then the body zones. */
    fun bodyUser(summary: String, bodyText: String): String = buildString {
        if (summary.isNotBlank()) append("ESTABLISHED FROM THE HEADER OF THE LETTER\n").append(summary).append("\n\n")
        append("LETTER\n").append(bodyText)
    }

    /** The user turn of the header session: nothing yet, every question brings its own zones. */
    const val HEADER_USER = "LETTER ZONES\nEach question below brings the zones it is about."

    /** "sender: M1 «Grundschule Am Waldweg»; addressee: M2 «Familie Beispiel»". Empty when nothing was established. */
    fun summary(items: List<Pair<String, String>>): String =
        items.joinToString("; ") { (role, who) -> "$role: $who" }

    /**
     * The zone block of one question: for every zone, its hint and (unless it is already in the session's
     * prefix) its text; then the candidates found there, and nothing else.
     *
     * @param inPrefix zones whose text the session's prefix already holds.
     */
    fun zoneBlock(
        zones: List<LetterZone>,
        hint: (LetterZone) -> String,
        text: (LetterZone) -> String,
        inPrefix: Set<LetterZone>,
        candidates: OfferedCandidates?,
        glimpse: ZonedLetter.Glimpse? = null,
    ): String = buildString {
        for (zone in zones) {
            append("ZONE ").append(zone.tag).append(". HINT: ").append(hint(zone)).append('\n')
            if (zone in inPrefix) append("(the text of this zone is in the letter above)\n") else append(text(zone)).append('\n')
        }
        if (glimpse != null) append(glimpseText(glimpse))
        if (candidates != null) {
            append("CANDIDATES IN THESE ZONES\n")
            append(if (candidates.size == 0) "(none)" else SelectionPrompt.table(candidates))
            append('\n')
        }
    }

    /**
     * The neighbouring zones' glimpse, labelled with their zone names and marked as context only: it tells the model
     * what sits around the zone it is deciding on, and nothing in it may be chosen.
     */
    fun glimpseText(g: ZonedLetter.Glimpse): String = buildString {
        g.before?.let { (zone, text) -> append("CONTEXT ONLY, the zone just above (").append(zone.tag).append(", not part of this question): ").append(text).append('\n') }
        g.after?.let { (zone, text) -> append("CONTEXT ONLY, the zone just below (").append(zone.tag).append(", not part of this question): ").append(text).append('\n') }
    }

    /**
     * What the letter calls a value the scoring picked as an extra: the printed words that name it. The value is the candidate
     * itself, so nothing here can change it; the model only names it. (A key was asked for as well and a 0.8B model answered
     * the format's own placeholder every time, so the key is left to the verifier's own fallback.)
     */
    fun extraName(candidate: String, context: ZonedLetter.Context?): Question {
        val ctx = listOfNotNull(
            context?.line?.takeIf { it.isNotBlank() }?.let { "it is printed on the line «$it»" },
            context?.above?.takeIf { it.isNotBlank() }?.let { "the line above it is «$it»" },
        )
        val where = if (ctx.isEmpty()) "" else ": ${ctx.joinToString(", ")}"
        return Question(
            "extra:name",
            "QUESTION: The value «$candidate» is an important fact of this letter$where. What does the letter call this value? " +
                "Copy the printed words that name it (not the value itself).\n" +
                "ANSWER FORMAT: the printed words in double quotes",
            QuestionGrammars.line(),
            NAME_TOKENS,
        )
    }

    private const val NAME_TOKENS = 40

    /** A statement of what a slot or a role is, for the scoring interpreter's question. */
    fun scoringQuestion(candidate: String, context: ZonedLetter.Context?, what: String): String = buildString {
        append("Is «").append(candidate).append("»")
        val ctx = listOfNotNull(
            context?.line?.takeIf { it.isNotBlank() && ZonedLetter.squash(it) != ZonedLetter.squash(candidate) }?.let { "printed on the line: $it" },
            context?.above?.takeIf { it.isNotBlank() }?.let { "line above: $it" },
            context?.below?.takeIf { it.isNotBlank() }?.let { "line below: $it" },
        )
        if (ctx.isNotEmpty()) append(" (context: ").append(ctx.joinToString("; ")).append(')')
        append(" ").append(what).append("? Answer:")
    }
}
