package com.postsaimanager.core.domain.extraction.gemma

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
        val letter = GemmaLetterBuilder.build(layout, request.offered, addressLines())
        if (letter.isImageOnly) return failed("the letter has no text lines")
        val category = request.forcedFamily?.let(schema::categoryOf)?.phrase

        val answered = when (val outcome = reader.read(GemmaReaderRequest(letter, imagePaths, category, onSummary))) {
            is GemmaReaderOutcome.Unavailable -> return failed(outcome.reason)
            is GemmaReaderOutcome.Answered -> outcome
        }
        lines += "t gemma reader (prefill, decode and the engine's own counters are in the engine's line) ms=${answered.ms}"
        lines += "gemma input lines=${letter.lines.size} candidates=${letter.candidates.size} image=${if (answered.usedImage) "yes" else "no"} " +
            "json=${answered.json.length} chars prompt=${answered.prompt.length} chars schema=${answered.schema.length} chars"

        val reading = when (val parsed = GemmaReadingParser.parse(answered.json)) {
            is GemmaReadingParser.Parsed.Ok -> parsed.reading
            is GemmaReadingParser.Parsed.Bad -> return InterpretationOutcome.Failed(parsed.reason, answered.json.take(FAILED_RAW_CHARS), answered.prompt, answered.schema)
        }
        val verified = verifier.verify(reading, letter, request.offered, layout.plainText(), letterDate())
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

        lines += "gemma decided category=${verified.category} sender=${party(verified, PartyRole.SENDER)} " +
            "addressee=${party(verified, PartyRole.ADDRESSEE)} contact=${party(verified, PartyRole.CONTACT)} " +
            "dates=${verified.dates.size} amounts=${verified.amounts.size} references=${verified.references.size} " +
            "actions=[${verified.actions.joinToString(",") { it.kind }}] name=${verified.name != null} paid=${verified.paid?.id} " +
            "dropped=${verified.drops.size}"
        verified.drops.forEach { lines += "gemma dropped: $it" }
        return InterpretationOutcome.Answered(raw,answered.json, answered.prompt, answered.schema)
    }

    /** The id the party was named by (a candidate id or a line's quote marker), never the printed text. */
    private fun party(v: VerifiedReading, role: PartyRole): String =
        v.parties.firstOrNull { it.role == role }?.let { it.candidateId ?: "line" } ?: "none"

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
