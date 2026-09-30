package com.postsaimanager.core.domain.extraction.v2

/**
 * One question of the questionnaire: what is asked, the grammar its answer must fit, and how many
 * tokens it may take. [name] is a stable label for diagnostics and recordings ("type", "sender",
 * "slot:total", "extras", "text:summary").
 */
class Question(val name: String, val text: String, val grammar: String, val maxTokens: Int)

/**
 * The instructions and the questions of the questionnaire interpreter. Pure text and data.
 *
 * English on purpose, like [SelectionPrompt]: the letter may be in any language and the answers are ids,
 * enums and quotes copied from it. Nothing here names a sender, a bank or a phrase; a party is described by
 * what it *does* in the letter (wrote it, is addressed by it, is what it is about, handles it). The per-slot
 * questions are not written here: they are data on the schema ([SlotKey.question], [DocType.description]).
 */
object QuestionnairePrompt {

    // Token caps per answer. The shapes are tiny; these leave room for a long quote and stop a runaway.
    const val TYPE_TOKENS = 16
    const val PARTY_TOKENS = 64
    const val ADDRESSEES_TOKENS = 160
    const val SLOT_TOKENS = 24
    const val RULE_SLOT_TOKENS = 64
    const val REFERENCES_TOKENS = 32
    const val EXTRAS_TOKENS = 280
    const val TITLE_TOKENS = 40
    const val SUBJECT_TOKENS = 56
    const val SUMMARY_TOKENS = 120
    const val SUGGESTIONS_TOKENS = 110

    /**
     * The tokens one question and its answer may add to the prefix, for budgeting the letter: the
     * longest question text (about 110 tokens) plus the longest answer.
     */
    const val QUESTION_RESERVE_TOKENS = 110 + EXTRAS_TOKENS

    private val RULES = """
        You read one scanned letter and answer questions about it, one question at a time. The letter can be in any language.

        INPUT. The letter text by page, with position tags in square brackets such as [letterhead],
        [address-field], [info-block], [subject], [body], [payment], [footer]. The tags only hint at
        where a line sits. Then CANDIDATES: values a program found, each with an id, the text as printed,
        a hint of the words next to it, and the page. The hint is not an answer.

        ANSWERS. Every answer is short and has exactly the shape the question asks for. Choose ids from the
        candidates. Never write a date, an amount or a number yourself. Write NONE when the letter has no such
        value. Decide from what the letter says, not from the order of the table. Confidence is LOW, MEDIUM or HIGH.
        A party's kind is PERSON, AUTHORITY, COMPANY or OTHER. Write a party's name as the letter prints it, without
        a form of address or a title. The sender and the addressee are never the same party.
    """.trimIndent()

    private val EXAMPLE = """
        EXAMPLE
        LETTER
        [letterhead] Grundschule Am Waldweg
        [address-field] Familie / Beispiel / Lindenstraße 4 / 12345 Musterstadt
        [info-block] Datum: 02.03.2026
        [body] Der Ausflug am 20.03.2026 kostet 12,00 € pro Kind. Bitte bis 10.03.2026 zurückgeben. Mia Beispiel nimmt teil.
        CANDIDATES
        M1: Grundschule Am Waldweg [letterhead] p.1
        M2: Familie Beispiel [address-field] p.1
        D1: 02.03.2026 (near: "Datum") p.1
        D2: 20.03.2026 p.1
        D3: 10.03.2026 p.1
        A1: 12,00 € p.1
        QUESTION: Who wrote and sent this letter?
        ANSWER: M1 AUTHORITY "Grundschule Am Waldweg" HIGH
        QUESTION: To whom is the letter addressed?
        ANSWER: M2 PERSON HOUSEHOLD "Familie Beispiel" HIGH
        QUESTION: Which date is the date of the letter itself?
        ANSWER: D1 LETTER_DATE HIGH
        QUESTION: By which date must the reader pay or act?
        ANSWER: D3 DEADLINE HIGH
        QUESTION: Which IBAN is the account to pay to?
        ANSWER: NONE
    """.trimIndent()

    fun system(withExample: Boolean): String = if (withExample) "$RULES\n\n$EXAMPLE" else RULES

    /** The user turn's body: the letter and the candidate table, ending where the first question begins. */
    fun user(layoutText: String, offered: OfferedCandidates): String = SelectionPrompt.user(layoutText, offered)

    /** How many candidate lines a question restates as its options. */
    const val OPTIONS_CAP = 10

    /**
     * [question] with the candidates it may choose from listed right after it (the same lines as the table in
     * the prefix, at most [OPTIONS_CAP]), so the choice sits next to the question. Costs question tokens.
     */
    fun withOptions(question: Question, offered: OfferedCandidates, vararg kinds: com.postsaimanager.core.domain.extraction.candidates.CandidateKind): Question {
        val ids = offered.idsOf(*kinds).toSet()
        val rows = offered.rows.filter { it.candidate.id in ids }.take(OPTIONS_CAP)
        if (rows.isEmpty()) return question
        return Question(question.name, question.text + "\nOPTIONS:\n" + SelectionPrompt.table(OfferedCandidates(rows)), question.grammar, question.maxTokens)
    }

    private fun question(name: String, ask: String, shape: String, grammar: String, maxTokens: Int) =
        Question(name, "QUESTION: $ask\nANSWER FORMAT: $shape", grammar, maxTokens)

    private const val CONFIDENCE = "CONFIDENCE"

    // ── the type ─────────────────────────────────────────────────────────────

    fun type(schema: ExtractionSchema): Question {
        val options = schema.types.joinToString("\n") { t ->
            if (t.description.isBlank()) "- ${t.id}" else "- ${t.id}: ${t.description}"
        }
        return question(
            "type",
            "What kind of document is this, and in which language is it written? The types are:\n$options",
            "TYPE LANGUAGE-CODE $CONFIDENCE (for example: bill de HIGH)",
            QuestionGrammars.type(schema),
            TYPE_TOKENS,
        )
    }

    // ── the parties ──────────────────────────────────────────────────────────

    private val PARTY_SHAPE = "ID KIND \"name\" $CONFIDENCE, or NONE"

    fun sender(offered: OfferedCandidates): Question = question(
        "sender",
        "Who wrote and sent this letter? Look at the letterhead, the sender line and the signature.",
        PARTY_SHAPE,
        QuestionGrammars.party(offered.idsOf(*SlotKind.NAME.candidates), withRelation = false, list = false),
        PARTY_TOKENS,
    )

    /** [excludeId]: the id already chosen as sender, which cannot also be the addressee. */
    fun addressee(offered: OfferedCandidates, excludeId: String?): Question = question(
        "addressee",
        "To whom is the letter addressed (the recipient in the address field)? If several people or parties are " +
            "addressed together, list up to ${QuestionGrammars.MAX_ADDRESSEES} separated by \"; \", the main addressee first. " +
            "RELATION is HOUSEHOLD when a family or household is addressed, GUARDIAN_OF when the addressee acts for another " +
            "person the letter is about (for example the parents of a child), otherwise NONE.",
        "ID KIND RELATION \"name\" $CONFIDENCE, or NONE",
        QuestionGrammars.party(
            offered.idsOf(*SlotKind.NAME.candidates).filter { it != excludeId },
            withRelation = true,
            list = true,
        ),
        ADDRESSEES_TOKENS,
    )

    fun subjectPerson(offered: OfferedCandidates): Question = question(
        "subject_person",
        "Who is the letter about, if that is a different person from the addressee (a patient, a child, the insured " +
            "person)? If it is about several, list up to ${QuestionGrammars.MAX_ADDRESSEES} separated by \"; \". " +
            "Answer NONE when it is about the addressee.",
        "ID KIND \"name\" $CONFIDENCE, or NONE",
        QuestionGrammars.party(offered.idsOf(*SlotKind.NAME.candidates), withRelation = false, list = true),
        ADDRESSEES_TOKENS,
    )

    fun contactPerson(offered: OfferedCandidates): Question = question(
        "contact",
        "Is a contact person named, or a person \"for the attention of\" or who handles the matter? Answer NONE when no one is.",
        PARTY_SHAPE,
        QuestionGrammars.party(offered.idsOf(*SlotKind.NAME.candidates), withRelation = false, list = false),
        PARTY_TOKENS,
    )

    fun careOf(offered: OfferedCandidates): Question = question(
        "care_of",
        "Is the letter sent in care of someone: a person or household whose address is only used as a mailbox for " +
            "the addressee (\"c/o\", \"care of\", \"bei\")? Answer NONE when it is not.",
        PARTY_SHAPE,
        QuestionGrammars.party(offered.idsOf(*SlotKind.NAME.candidates), withRelation = false, list = false),
        PARTY_TOKENS,
    )

    // ── the slots ────────────────────────────────────────────────────────────

    /** The question of [slot], or null when the offered candidates leave nothing to choose from (the answer is NONE). */
    fun slot(slot: SlotKey, offered: OfferedCandidates): Question? {
        val grammar = QuestionGrammars.slot(slot, offered) ?: return null
        val shape = when (slot.kind) {
            SlotKind.AMOUNT -> "ID ROLE $CONFIDENCE (ROLE is one of ${Roles.AMOUNT.joinToString(", ")}), or NONE"
            SlotKind.DATE -> "ID ROLE $CONFIDENCE (ROLE is one of ${Roles.DATE.joinToString(", ")}), or NONE"
            SlotKind.DEADLINE ->
                "ID ROLE $CONFIDENCE, or RULE \"words\" ROLE $CONFIDENCE (ROLE is one of ${Roles.DATE.joinToString(", ")}), or NONE"
            SlotKind.IBAN, SlotKind.REFERENCE -> "ID $CONFIDENCE, or NONE"
            SlotKind.REFERENCE_LIST -> "ID ID ... $CONFIDENCE, or NONE"
            SlotKind.NAME -> "ID $CONFIDENCE, or \"name\" $CONFIDENCE, or NONE"
            SlotKind.ACTION -> "ACTION $CONFIDENCE (ACTION is one of ${SlotKey.ACTIONS.joinToString(", ")}), or NONE"
        }
        val ask = slot.question.ifBlank { "Which value is the ${slot.label.lowercase()}?" }
        val tokens = when (slot.kind) {
            SlotKind.DEADLINE -> RULE_SLOT_TOKENS
            SlotKind.REFERENCE_LIST -> REFERENCES_TOKENS
            else -> SLOT_TOKENS
        }
        return question("slot:${slot.json}", ask, shape, grammar, tokens)
    }

    // ── extras ───────────────────────────────────────────────────────────────

    /** [taken]: ids a party or a slot already used. */
    fun extras(offered: OfferedCandidates, taken: Set<String>): Question = question(
        "extras",
        "List up to ${StructuredGrammar.MAX_EXTRAS} other important facts of this letter that the earlier questions did not " +
            "cover (a meter number, a tariff, a policy holder, a vehicle plate, a school class, a phone number to call ...). " +
            "Separate the facts with \"; \". Answer NONE when there are none. Use only ids that were not answered before.",
        "ID \"label as printed\" english_key \"value copied from the letter, empty when an id is given\" $CONFIDENCE, " +
            "or NONE \"label\" english_key \"value\" $CONFIDENCE when no id fits",
        QuestionGrammars.extras(QuestionGrammars.remainingIds(offered, taken)),
        EXTRAS_TOKENS,
    )

    /** The letter's language as a BCP-47 code, asked on its own: a small model answers a plain question, not one that asks for a list as well. */
    fun language(): Question = question(
        "lang",
        "In which language is this letter written? Answer with its BCP-47 language code (for example de, en, ar).",
        "the code only",
        QuestionGrammars.language(),
        LANGUAGE_TOKENS,
    )

    private const val LANGUAGE_TOKENS = 8

    // ── the free text ────────────────────────────────────────────────────────

    private const val IN_LETTER_LANGUAGE = "Write it in the letter's own language."

    @Deprecated("The title is composed from verified fields (TitleComposer); P4 removes this with the title ask in ZoneFreeText/the interpreter.")
    fun otherLabel(): Question = question(
        "text:other",
        "Give a short name for this kind of document. $IN_LETTER_LANGUAGE",
        "one line of text in double quotes",
        QuestionGrammars.line(),
        TITLE_TOKENS,
    )

    @Deprecated("The title is composed from verified fields (TitleComposer); P4 removes this with the title ask in ZoneFreeText/the interpreter.")
    fun title(typeId: String?): Question = question(
        "text:title",
        "Write a title of at most 8 words for this ${typeId?.replace('_', ' ') ?: "document"}: who wrote it and what it is for. $IN_LETTER_LANGUAGE",
        "one line of text in double quotes",
        QuestionGrammars.line(),
        TITLE_TOKENS,
    )

    fun subjectLine(): Question = question(
        "text:subject",
        "Copy the subject line of the letter exactly as it is printed.",
        "the subject line in double quotes",
        QuestionGrammars.line(),
        SUBJECT_TOKENS,
    )

    fun summary(): Question = question(
        "text:summary",
        "Write one or two sentences saying what the reader must know or do. Copy sentences from the letter where you can. $IN_LETTER_LANGUAGE",
        "one or two sentences in double quotes",
        QuestionGrammars.line(),
        SUMMARY_TOKENS,
    )

    fun suggestedQuestions(): Question = question(
        "text:questions",
        "Write three short questions the reader may ask about the content of this letter (for example about an amount, " +
            "a deadline or what to do next). $IN_LETTER_LANGUAGE",
        "three questions, each in double quotes, separated by spaces",
        QuestionGrammars.threeLines(),
        SUGGESTIONS_TOKENS,
    )
}
