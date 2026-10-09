package com.postsaimanager.core.domain.memory

/**
 * The shape of the session-end answer, and the one place that knows it: the question and the parser that reads the answer.
 *
 * ```
 * The user already paid the invoice by bank transfer.
 * The user will call the Jobcenter on Monday.
 * ```
 * Up to [MAX_NOTES] lines, each a short statement of at most [MAX_NOTE_CHARS] characters, or the single word [NONE]. The model words
 * the notes in the language the user wrote in; code never interprets them, it only verifies them ([SessionNoteVerifier]). The LiteRT-LM
 * conversation API offers no grammar, so the shape is asked for in words and read leniently (bullets and numbering are dropped); what
 * does not fit the limits is dropped by the verifier, never trusted.
 */
object SessionNotesFormat {

    const val MAX_NOTES = 3
    const val MAX_NOTE_CHARS = 140

    /** What the notes of a document's chat are about, as the question words it. */
    const val ABOUT_DOCUMENT = "this document"

    /** What the notes of the all-documents chat are about. */
    const val ABOUT_HOUSEHOLD = "the user's household and the people in it"

    /** The answer when nothing from the conversation is worth keeping. */
    const val NONE = "NONE"

    /** The decode budget of the one generation: three lines of about 40 tokens. */
    const val MAX_TOKENS = 160

    /** How much of the conversation the question carries: the newest part, as the notes are about what was said last. */
    const val MAX_TRANSCRIPT_CHARS = 3_000

    /** A turn as the question shows it. */
    data class Turn(val fromUser: Boolean, val text: String)

    /** The standing instruction of the one generation. */
    const val SYSTEM =
        "You write short durable notes for a document assistant. You only report what the user said they did, decided or asked to be " +
            "remembered. A question the user asked is not a fact: you never write a question, or what the user only asked about, as a note. " +
            "You never add facts, numbers or dates that are not in the user's own messages."

    /**
     * The question: the conversation (newest part, within [MAX_TRANSCRIPT_CHARS]), the notes already kept (so they are not repeated),
     * and the instruction. The wording is the plan's: durable facts or decisions that matter for this document later.
     */
    fun prompt(
        turns: List<Turn>,
        existingNotes: List<String>,
        about: String = ABOUT_DOCUMENT,
        actionNotes: List<String> = emptyList(),
        /** The code of the language the notes are written in (the app's language); the language the user wrote in when null. */
        languageCode: String? = null,
    ): String = buildString {
        append("CONVERSATION:\n")
        append(transcript(turns))
        append("\n\nNOTES ALREADY KEPT (do not repeat them):\n")
        if (existingNotes.isEmpty()) append("- none\n") else existingNotes.forEach { append("- ").append(it).append('\n') }
        if (actionNotes.isNotEmpty()) {
            append("\nACTIONS ALREADY RECORDED (the app wrote these when the user confirmed them; never restate them):\n")
            actionNotes.forEach { append("- ").append(it).append('\n') }
        }
        append("\nQUESTION: List up to ").append(MAX_NOTES)
        append(" durable facts or decisions from this conversation that matter for ").append(about).append(" later. ")
        append("First look for what the USER stated about their own situation or decisions: something they already paid, booked, ")
        append("sent or arranged, or something they decided to do. A question of the user is not a fact (\"Did I already pay?\" says ")
        append("nothing about whether they paid): never turn a question into a note. A request or command the user gave the assistant (to ")
        append("remind them, write, send or add something) is not a fact either: never write it as a note. Do not write what the assistant ")
        append("did or offered (reminders, calendar entries, drafts): those are recorded already. One note per line, at most ").append(MAX_NOTE_CHARS)
        append(" characters each, in ")
        append(languageCode?.trim()?.takeIf { it.isNotEmpty() }?.let { "the language with the code \"$it\"" } ?: "the language the user wrote in")
        append(". Answer ").append(NONE).append(" if nothing.")
    }

    private fun transcript(turns: List<Turn>): String {
        val lines = turns.filter { it.text.isNotBlank() }.map { (if (it.fromUser) "User: " else "Assistant: ") + it.text.trim().replace('\n', ' ') }
        val kept = ArrayList<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length + 1 > MAX_TRANSCRIPT_CHARS && kept.isNotEmpty()) break
            kept += if (line.length > MAX_TRANSCRIPT_CHARS) line.take(MAX_TRANSCRIPT_CHARS) else line
            used += line.length + 1
        }
        return kept.asReversed().joinToString("\n")
    }

    /** The candidate notes of an answer, in order; empty for [NONE] or nothing readable. At most twice [MAX_NOTES] are read (the verifier keeps [MAX_NOTES]). */
    fun parse(answer: String): List<String> {
        val text = answer.trim()
        if (text.isEmpty() || NONE_ONLY.matches(text)) return emptyList()
        return text.lines()
            .map { line -> LEAD.replace(line.trim(), "").trim().trim('"', '“', '”').trim() }
            .filter { it.isNotEmpty() && !NONE_ONLY.matches(it) }
            .take(MAX_NOTES * 2)
    }

    /** "NONE" alone, in any case, with or without a closing full stop. */
    private val NONE_ONLY = Regex("(?i)\\s*none\\s*[.!]?\\s*")

    /** A list marker at the start of a line: "- ", "* ", "• ", "1. ", "2) ". */
    private val LEAD = Regex("^(?:[-*•–]|\\d{1,2}[.)])\\s+")
}
