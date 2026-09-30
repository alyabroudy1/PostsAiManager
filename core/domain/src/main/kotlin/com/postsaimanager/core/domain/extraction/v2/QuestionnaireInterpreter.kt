package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession

/** One question as it was asked, for diagnostics and recordings. [answer] is null when the engine failed it. */
class AskRecord(
    val name: String,
    val question: String,
    val answer: String?,
    val ms: Long,
    /** Tokens of the question text and of the answer; -1 unless the interpreter was asked to count them. */
    val questionTokens: Int = -1,
    val answerTokens: Int = -1,
)

/**
 * "Read once, ask many short questions": the same [DocumentInterpreter] as [ModelDocumentInterpreter],
 * producing the same [RawInterpretation] and [RawText], but by reading the letter into a [PromptSession]
 * once and asking a small model many tiny, focused, grammar-constrained questions instead of one big JSON.
 *
 * A small model does better on a single multiple-choice question than on choosing every slot, party and
 * extra at once (measured: one big call gave 51% recall and put the same candidate into every role). Here
 * each answer is one choice among the offered ids, so the model cannot spread one candidate over many
 * roles by pattern, and the questions are independent of each other because every one rolls back to the
 * shared prefix (the letter and the candidate table, decoded once).
 *
 * Order: the type; the sender, the addressee(s), whom the letter is about, a contact person; one question
 * per slot of the chosen type (from the schema registry, [SlotKey.question]); the remaining facts; then,
 * from [writeText], the title, the subject line, the summary and three suggested questions, in the same
 * session so the letter is not read a second time.
 *
 * AI decides, code verifies: nothing here decides what a value means. The grammar only limits an answer to
 * the offered ids of the right kind; the one thing code excludes is the invariant that the sender is not
 * also the addressee (the sender's id is left out of the addressee question's grammar). The verifier does the
 * rest on the same [RawInterpretation] as before.
 *
 * @param engine used only for the model's own chat template ([AiEngine.formatPrompt]).
 * @param measureTokens count the tokens of each question and answer (one extra call each), for the benchmark.
 */
class QuestionnaireInterpreter(
    private val engine: AiEngine,
    private val session: PromptSession,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    contextTokens: Int,
    private val measureTokens: Boolean = false,
    /** Restate the candidates a question chooses from right after the question (see [QuestionnairePrompt.withOptions]). */
    private val restateOptions: Boolean = false,
) : DocumentInterpreter {

    private fun names(q: Question, offered: OfferedCandidates) =
        if (restateOptions) QuestionnairePrompt.withOptions(q, offered, *SlotKind.NAME.candidates) else q

    private val withExample = contextTokens >= SelectionPrompt.EXAMPLE_MIN_CONTEXT_TOKENS

    override val maxAnswerTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS
    override val maxTextTokens: Int = QuestionnairePrompt.QUESTION_RESERVE_TOKENS

    /** Every question asked since the last [interpret], in order. */
    val transcript: List<AskRecord> get() = records
    private val records = ArrayList<AskRecord>()

    /** Tokens and milliseconds the last [interpret] spent reading the prefix, 0 before one. */
    var prefixTokens: Int = 0
        private set
    var prefixMs: Long = 0
        private set

    private var prefix: String? = null
    private var tail: String = ""
    private var consecutiveFailures = 0

    override fun promptOverheadChars(offered: OfferedCandidates): Int =
        QuestionnairePrompt.system(withExample).length + SelectionPrompt.table(offered).length + FRAME_CHARS

    override fun textOverheadChars(): Int = FRAME_CHARS

    override suspend fun promptOverheadTokens(offered: OfferedCandidates): Int? {
        val system = session.countTokens(QuestionnairePrompt.system(withExample)) ?: return null
        val table = session.countTokens(SelectionPrompt.table(offered)) ?: return null
        return system + table + FRAME_TOKENS
    }

    override suspend fun countTokens(text: String): Int? = session.countTokens(text)

    private class Abort(val reason: String) : Exception(reason)

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        records.clear()
        consecutiveFailures = 0
        val offered = request.offered

        val (head, closing) = frame(QuestionnairePrompt.system(withExample), QuestionnairePrompt.user(request.layoutText, offered))
        prefix = head
        tail = closing

        val started = System.nanoTime()
        val opened = session.open(head)
        prefixMs = (System.nanoTime() - started) / NANOS_PER_MS
        if (opened is PamResult.Error) {
            return InterpretationOutcome.Failed("the model could not read the letter: ${opened.error.userMessage}", null, head, "")
        }
        prefixTokens = (opened as PamResult.Success).data

        val typeQuestion = QuestionnairePrompt.type(schema)
        return try {
            val raw = readEverything(offered)
            InterpretationOutcome.Answered(raw, transcriptText(), head, typeQuestion.grammar)
        } catch (e: Abort) {
            session.close()
            InterpretationOutcome.Failed(e.reason, transcriptText().take(FAILED_RAW_CHARS), head, typeQuestion.grammar)
        }
    }

    private suspend fun readEverything(offered: OfferedCandidates): RawInterpretation {
        val type = AnswerReader.type(ask(QuestionnairePrompt.type(schema)).orEmpty())
            ?: throw Abort("the model gave no document type")
        val docType = schema.family(type.typeId) ?: throw Abort("the model chose a document type that does not exist: ${type.typeId}")

        val taken = LinkedHashSet<String>()
        fun take(id: String?) {
            if (id != null && offered.get(id) != null) taken += id
        }

        // ── parties ──
        val parties = ArrayList<RawParty>()
        fun add(role: PartyRole, answer: PartyAnswer) {
            take(answer.id)
            parties += RawParty(
                role = role.name, id = answer.id, kind = answer.kind, relation = answer.relation,
                confidence = answer.confidence, name = answer.name.ifBlank { null },
            )
        }
        val sender = AnswerReader.parties(ask(names(QuestionnairePrompt.sender(offered), offered)).orEmpty(), withRelation = false).firstOrNull()
        sender?.let { add(PartyRole.SENDER, it) }
        val addressees = AnswerReader
            .parties(ask(names(QuestionnairePrompt.addressee(offered, sender?.id), offered)).orEmpty(), withRelation = true)
            .filter { it.id != sender?.id }
            .take(QuestionGrammars.MAX_ADDRESSEES)
        addressees.forEachIndexed { i, a -> add(if (i == 0) PartyRole.ADDRESSEE else PartyRole.CO_ADDRESSEE, a) }
        AnswerReader.parties(ask(names(QuestionnairePrompt.subjectPerson(offered), offered)).orEmpty(), withRelation = false)
            .take(QuestionGrammars.MAX_ADDRESSEES)
            .forEach { add(PartyRole.SUBJECT_PERSON, it) }
        AnswerReader.parties(ask(names(QuestionnairePrompt.contactPerson(offered), offered)).orEmpty(), withRelation = false).firstOrNull()
            ?.let { add(PartyRole.ROUTING, it) }
        AnswerReader.parties(ask(names(QuestionnairePrompt.careOf(offered), offered)).orEmpty(), withRelation = false).firstOrNull()
            ?.let { add(PartyRole.CARE_OF, it) }

        // ── slots of the chosen type ──
        val slots = LinkedHashMap<String, RawSlot>()
        for (slot in docType.slots) {
            val base = QuestionnairePrompt.slot(slot, offered) ?: continue
            val question = if (restateOptions) QuestionnairePrompt.withOptions(base, offered, *slot.kind.candidates) else base
            val answer = AnswerReader.slot(slot, ask(question).orEmpty()) ?: continue
            slots[slot.json] = answer
            take(answer.id)
            answer.ids.forEach { take(it) }
        }

        // ── the rest ──
        val extras = AnswerReader.extras(ask(QuestionnairePrompt.extras(offered, taken)).orEmpty())
            .take(StructuredGrammar.MAX_EXTRAS)

        return RawInterpretation(
            type = type.typeId,
            typeConfidence = type.confidence,
            language = type.language,
            parties = parties.take(StructuredGrammar.MAX_PARTIES),
            slots = slots,
            extras = extras,
        )
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        if (prefix == null) return TextOutcome.Failed("the letter was not read")
        consecutiveFailures = 0
        try {
            // Same session as [interpret]: the letter is not read again (only if the engine lost the state
            // in between, which the PromptSession implementation repairs by itself).
            val other = if (request.documentTypeId == ExtractionSchema.OTHER.id) {
                AnswerReader.line(askOrNull(QuestionnairePrompt.otherLabel()).orEmpty())
            } else {
                null
            }
            val title = AnswerReader.line(askOrNull(QuestionnairePrompt.title(request.documentTypeId)).orEmpty())
            val subject = AnswerReader.line(askOrNull(QuestionnairePrompt.subjectLine()).orEmpty())
            val summary = AnswerReader.line(askOrNull(QuestionnairePrompt.summary()).orEmpty())
            val questions = AnswerReader.lines(askOrNull(QuestionnairePrompt.suggestedQuestions()).orEmpty())
            if (title == null && subject == null && summary == null && questions.isEmpty()) {
                return TextOutcome.Failed("the model wrote no text")
            }
            val text = RawText(
                otherLabel = other?.take(TextGrammar.MAX_OTHER_CHARS),
                title = title?.take(TextGrammar.MAX_TITLE_CHARS),
                subject = subject?.take(TextGrammar.MAX_SUBJECT_CHARS),
                summary = summary?.take(TextGrammar.MAX_SUMMARY_CHARS),
                questions = questions.map { it.take(TextGrammar.MAX_QUESTION_CHARS) }.take(TextGrammar.MAX_QUESTIONS),
            )
            return TextOutcome.Written(text, records.filter { it.name.startsWith("text:") }.joinToString("\n") { "${it.name}: ${it.answer}" })
        } finally {
            // The free text is the last thing asked about this letter.
            session.close()
        }
    }

    /** [ask] for the free text, where a failed question is just a missing text, never an abort. */
    private suspend fun askOrNull(question: Question): String? = try {
        ask(question)
    } catch (e: Abort) {
        null
    }

    /** The answer, or null when the engine failed the question. Three failures in a row abort the reading. */
    private suspend fun ask(question: Question): String? {
        val started = System.nanoTime()
        val result = session.ask("\n\n" + question.text + tail, question.grammar, question.maxTokens)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val answer = (result as? PamResult.Success)?.data?.trim()
        records += AskRecord(
            name = question.name,
            question = question.text,
            answer = answer,
            ms = ms,
            questionTokens = if (measureTokens) session.countTokens(question.text + tail) ?: -1 else -1,
            answerTokens = if (measureTokens && answer != null) session.countTokens(answer) ?: -1 else -1,
        )
        if (answer == null) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw Abort("the engine failed $MAX_CONSECUTIVE_FAILURES questions in a row")
            }
        } else {
            consecutiveFailures = 0
        }
        return answer
    }

    private fun transcriptText(): String = records.joinToString("\n") { "${it.name}: ${it.answer}" }

    /**
     * Renders the letter as the model's chat template wants it and cuts the result where a question
     * goes: everything before is the prefix (decoded once), everything after is what closes the user
     * turn and opens the assistant's, appended to every question. A template that swallows the marker
     * gives the whole prompt as prefix and no tail.
     */
    private fun frame(system: String, body: String): Pair<String, String> {
        val rendered = engine.formatPrompt(
            listOf(AiChatMessage(AiChatRole.SYSTEM, system), AiChatMessage(AiChatRole.USER, body + MARK)),
        )
        val at = rendered.lastIndexOf(MARK)
        return if (at < 0) rendered to "" else rendered.substring(0, at) to rendered.substring(at + MARK.length)
    }

    companion object {
        private const val MARK = "@@QUESTION@@"
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val FAILED_RAW_CHARS = 300

        /** The template's own markers and the headings around the letter, in characters and in tokens. */
        private const val FRAME_CHARS = 120
        private const val FRAME_TOKENS = 64
    }
}
