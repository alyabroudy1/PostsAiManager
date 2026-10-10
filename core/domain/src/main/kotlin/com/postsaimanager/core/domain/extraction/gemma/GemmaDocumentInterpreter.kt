package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.LabelValuePair
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.InterpretationOutcome
import com.postsaimanager.core.domain.extraction.v2.InterpretationRequest
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.RawText
import com.postsaimanager.core.domain.extraction.v2.TextOutcome
import com.postsaimanager.core.domain.extraction.v2.TextRequest
import java.time.LocalDate

/**
 * "Gemma reads the letter" as a [DocumentInterpreter]: the one call that decides the type, who is who and what every value means, in
 * the terms the extraction pipeline already runs on. The pipeline finds the lines and the candidates (the shape finders and ML Kit) and
 * builds the result with the Gemma path's own verifier ([GemmaResultVerifier], over the checks below), not the scoring reading's caps;
 * this class only turns the letter into the reader's input and the reader's JSON back into a [com.postsaimanager.core.domain.extraction.v2.RawInterpretation].
 *
 * In between, [GemmaReadingVerifier] checks what code can show wrong (an id that is not in the letter, a sender that is also the
 * addressee, a date that does not parse, a due date before the letter's date, an IBAN with a wrong checksum, a name or a fact the letter
 * does not hold): a wrong answer is not kept as it was said, and what the model chose is shown as a value to check, never silently empty.
 *
 * Not staged: one call reads everything but the two long free texts, the summary and the key facts, which a second, lower-priority step
 * writes once the reading is stored ([GemmaTextWriter]); the name comes with the call ([decision]), and [writeText] has nothing left to ask.
 *
 * @param addressLines the lines ML Kit found an address in (context for the reader), read when the reading starts
 * @param letterDate the date of the letter as code found it, read when the reading starts
 */
class GemmaDocumentInterpreter(
    private val reader: GemmaDocumentReader,
    private val imagePaths: List<String>,
    private val addressLines: () -> Set<Int> = { emptySet() },
    private val letterDate: () -> LocalDate? = { null },
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    private val verifier: GemmaReadingVerifier = GemmaReadingVerifier(),
    private val mapper: GemmaReadingMapper = GemmaReadingMapper(),
    private val addressReader: GemmaAddresses = GemmaAddresses(),
    private val found: GemmaFoundValues = GemmaFoundValues(),
    private val onSummary: (suspend (String) -> Unit)? = null,
    private val keepOpenAs: String? = null,
    private val summaryLanguage: String? = null,
    private val labelPairs: () -> List<LabelValuePair> = { emptyList() },
    private val ocrDump: () -> List<String> = { emptyList() },
) : DocumentInterpreter {

    override val name: String = "gemma-reader"

    override val maxAnswerTokens: Int = ANSWER_TOKENS

    override val maxTextTokens: Int = 0

    override fun promptOverheadChars(offered: OfferedCandidates): Int = 0

    override fun textOverheadChars(): Int = 0

    private val lines = mutableListOf<String>()

    override val trace: List<String> get() = lines.toList()

    /** What the last [interpret] kept of the model's answer: the pipeline's result carries the slots and parties, this the rest. */
    var decision: GemmaDecision? = null
        private set

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        lines.clear()
        decision = null
        val layout = request.layout ?: return failed("the reader needs the letter's layout")
        val letter = GemmaLetterBuilder.build(layout, request.offered, addressLines(), labelPairs())
        if (letter.isImageOnly) return failed("the letter has no text lines")
        lines += ocrDump() // debug only: the OCR blocks with their boxes (to turn a device letter into a test fixture)
        val category = request.forcedFamily?.let(schema::categoryOf)?.phrase

        val answered = when (val outcome = reader.read(GemmaReaderRequest(letter, imagePaths, category, onSummary, keepOpenAs, summaryLanguage))) {
            is GemmaReaderOutcome.Unavailable -> return failed(outcome.reason)
            is GemmaReaderOutcome.Stated -> return interpretStated(outcome, request, letter)
            is GemmaReaderOutcome.Answered -> outcome
        }
        lines += "t gemma reader (prefill, decode and the engine's own counters are in the engine's line) ms=${answered.ms}"
        lines += "gemma input lines=${letter.lines.size} candidates=${letter.candidates.size} image=${if (answered.usedImage) "yes" else "no"} " +
            "json=${answered.json.length} chars prompt=${answered.prompt.length} chars schema=${answered.schema.length} chars"

        lines += "gemma party choices: " + GemmaSchema.partyIds(letter).joinToString(" ") { id ->
            letter.line(id)?.let { "$id[${it.zone}]=${it.text.take(40)}" } ?: letter.candidates.firstOrNull { it.id == id }?.let { "$id=${it.raw.take(40)}" } ?: id
        } + " | labels: " + letter.lines.filter { it.isLabel }.joinToString(" ") { "${it.id}=${it.text.take(30)}" }

        val reading = when (val parsed = GemmaReadingParser.parse(answered.json)) {
            is GemmaReadingParser.Parsed.Ok -> parsed.reading
            is GemmaReadingParser.Parsed.Bad -> return InterpretationOutcome.Failed(parsed.reason, answered.json.take(FAILED_RAW_CHARS), answered.prompt, answered.schema)
        }
        val verified = verifier.verify(reading, letter, request.offered, layout.plainText(), letterDate())
        return finish(verified, letter, request, layout, answered.json, answered.prompt, answered.schema)
    }

    /**
     * The "Questions" reader's answer: parsed by its labels and mapped as it is ([QuestionReadingBuilder]); no check of [GemmaReadingVerifier]
     * runs over it (the person confirms what is stored).
     */
    private suspend fun interpretStated(outcome: GemmaReaderOutcome.Stated, request: InterpretationRequest, letter: GemmaLetter): InterpretationOutcome {
        val layout = request.layout ?: return failed("the reader needs the letter's layout")
        lines += "t gemma reader (questions) ms=${outcome.ms}"
        lines += "gemma input lines=${letter.lines.size} image=${if (outcome.usedImage) "yes" else "no"} answer=${outcome.text.length} chars prompt=${outcome.prompt.length} chars"
        lines += outcome.notes
        // The model's raw answer, in the reading trace: its lines are logged in a debuggable build only (see the pipeline's logReadingTrace).
        outcome.text.trim().lines().forEach { lines += "qa raw: $it" }
        val answers = QuestionAnswerParser.parse(outcome.text)
        if (answers.answered.isEmpty()) return InterpretationOutcome.Failed("the answer has none of the asked labels", outcome.text.take(FAILED_RAW_CHARS), outcome.prompt, "")
        val verified = QuestionReadingBuilder().build(answers, letter, request.offered)
        return finish(verified, letter, request, layout, outcome.text, outcome.prompt, "")
    }

    private suspend fun finish(
        verified: VerifiedReading, letter: GemmaLetter, request: InterpretationRequest, layout: com.postsaimanager.core.domain.extraction.layout.LetterLayout,
        rawAnswer: String, prompt: String, schemaText: String,
    ): InterpretationOutcome {
        decision = GemmaDecision(verified)
        val mapped = mapper.map(verified, verified.language, request.direction, request.forcedFamily)
        // What code found and the answer lacks (the addresses, the letter's own date), added apart from the mapping (see GemmaFoundValues).
        val addresses = try {
            addressReader.read(layout, request.offered, verified)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lines += "addresses not read: ${e.javaClass.simpleName}"
            null
        }
        val raw = found.apply(mapped.raw, addresses, found.candidateOf(letter, request.offered))

        lines += "gemma decided category=${verified.category} sender=${party(verified, PartyRole.SENDER, letter)} " +
            "addressee=${party(verified, PartyRole.ADDRESSEE, letter)} contact=${party(verified, PartyRole.CONTACT, letter)} " +
            "dates=${verified.dates.size} amounts=${verified.amounts.size} references=${verified.references.size} " +
            "actions=[${verified.actions.joinToString(",") { it.kind }}] name=${verified.name != null} paid=${verified.paid?.id} " +
            "dropped=${verified.drops.size}"
        verified.drops.forEach { lines += "gemma dropped: $it" }
        verified.notes.forEach { lines += "qa note: $it" }
        return InterpretationOutcome.Answered(raw, rawAnswer, prompt, schemaText)
    }

    /** The party that was decided: its candidate id (or "line" for a printed line) and the text, for the debug log. */
    private fun party(v: VerifiedReading, role: PartyRole, letter: GemmaLetter): String =
        v.parties.firstOrNull { it.role == role }?.let { p ->
            (p.candidateId ?: "line") + "='" + (p.candidateId?.let { letter.candidate(it)?.raw } ?: p.quote).orEmpty().take(40) + "'/" + p.kind.name.lowercase()
        } ?: "none"

    private fun failed(reason: String): InterpretationOutcome {
        lines += "gemma unavailable: $reason"
        return InterpretationOutcome.Failed(reason, null, "", "")
    }

    override suspend fun writeText(request: TextRequest): TextOutcome =
        TextOutcome.Written(RawText(otherLabel = null, title = null, subject = null, summary = null, questions = emptyList()), "")

    private companion object {
        const val ANSWER_TOKENS = 512
        const val FAILED_RAW_CHARS = 300
    }
}

/** What the model decided beyond the slots and parties the pipeline carries: the texts it wrote and the actions it found, all verified. */
class GemmaDecision(val verified: VerifiedReading)
