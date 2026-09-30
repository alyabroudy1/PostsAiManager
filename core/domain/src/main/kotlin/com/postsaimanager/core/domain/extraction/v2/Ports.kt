package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock

/**
 * The seams of extraction v2. Each unit does one job and can be replaced or faked on its own:
 *
 * ```
 * pages ──LayoutReader──▶ LetterLayout ──CandidateSource──▶ candidates ──CandidateTable──▶ offered
 *   call 1  DocumentInterpreter.interpret  (ids, enums, confidence)   ──▶ RawInterpretation
 *   call 2  DocumentInterpreter.writeText  (title, subject, summary)  ──▶ RawText
 *   ResultVerifier ──▶ ExtractionV2Result ──UnderstandingAdapter──▶ DocumentUnderstanding
 * ```
 */

/** Turns OCR blocks into a page-by-page layout (zones are hints). Workstream B. */
fun interface LayoutReader {
    fun read(pages: List<List<OcrBlock>>): LetterLayout
}

/** Finds the value-shaped things in the pages. Workstream C. No meaning is decided here. */
fun interface CandidateSource {
    fun find(pages: List<List<OcrBlock>>, layout: LetterLayout): CandidateSet
}

/** What call 1 is given. */
class InterpretationRequest(
    /** The letter as the model should read it, already within budget. */
    val layoutText: String,
    val offered: OfferedCandidates,
    /**
     * The zoned layout the text was made from, for interpreters that read the letter zone by zone
     * (`ZoneInterpreter`); the others ignore it.
     */
    val layout: LetterLayout? = null,
    /** Width over height of the first page when known, for the layout template match; null otherwise. */
    val pageAspect: Float? = null,
    /**
     * Whose document this is. Known before reading (how it entered the app), it narrows the types offered
     * ([ExtractionSchema.typesFor]); incoming until the app stores a direction per document.
     */
    val direction: DocDirection = DocDirection.INCOMING,
)

/** What call 2 is given: the letter again (the engine starts every call from an empty cache) and what call 1 decided. */
class TextRequest(
    val layoutText: String,
    /** The document type call 1 chose, so the texts fit it; null when call 1 failed. */
    val documentTypeId: String?,
)

/**
 * The only place that talks to a model, through the `AiEngine` port and nothing else. Two calls,
 * both grammar-constrained and greedy:
 * - [interpret] decides the type, what every value means and who is who: ids, enums, confidence.
 * - [writeText] writes the free text: title, subject, summary, suggested questions.
 *
 * They are separate because one call does not fit the 4K window with the letter, the candidate table
 * and a long answer; the engine clears its cache on every call, so the second one prefills again.
 */
interface DocumentInterpreter {

    /** Tokens call 1 may use; the pipeline subtracts them from the context window. */
    val maxAnswerTokens: Int

    /** Tokens call 2 may use. */
    val maxTextTokens: Int

    /** Characters the fixed part of call 1's prompt (instructions, example, candidate table) will cost. */
    fun promptOverheadChars(offered: OfferedCandidates): Int

    /** Characters the fixed part of call 2's prompt will cost. */
    fun textOverheadChars(): Int

    /**
     * The fixed part of the prompt in tokens, counted by the model's own tokenizer; null (the default)
     * when the interpreter cannot count, and the pipeline budgets the letter from the character estimate.
     * When both this and [countTokens] answer, the letter is fitted by measured tokens instead.
     */
    suspend fun promptOverheadTokens(offered: OfferedCandidates): Int? = null

    /** [text] in tokens by the model's own tokenizer, or null when it cannot be counted. */
    suspend fun countTokens(text: String): Int? = null

    suspend fun interpret(request: InterpretationRequest): InterpretationOutcome

    suspend fun writeText(request: TextRequest): TextOutcome
}

sealed interface InterpretationOutcome {
    /** The prompt and grammar that were sent, kept for diagnostics and tests. */
    val prompt: String
    val grammar: String

    class Answered(
        val raw: RawInterpretation,
        val rawText: String,
        override val prompt: String,
        override val grammar: String,
    ) : InterpretationOutcome

    /** The model could not be run or its answer could not be read. Extraction degrades to found values. */
    class Failed(
        val reason: String,
        val rawText: String?,
        override val prompt: String,
        override val grammar: String,
    ) : InterpretationOutcome
}

sealed interface TextOutcome {
    class Written(val text: RawText, val rawText: String) : TextOutcome

    class Failed(val reason: String) : TextOutcome
}

/** One party as the model wrote it: [id] is a name candidate id or a quoted name, never checked yet. */
data class RawParty(
    val role: String,
    val id: String,
    val kind: String?,
    val relation: String?,
    /** The model's own confidence word (LOW, MEDIUM, HIGH), as written. */
    val confidence: String? = null,
    /**
     * The party's normalised name as the model writes it (without a form of address or a title).
     * Used only when its words are found in the text of the chosen candidate or quote.
     */
    val name: String? = null,
    /**
     * Set by an interpreter that read the letter by zones when the answer contradicts what the zone
     * usually holds (a name outside the address field as addressee). Allowed, but the verifier caps it.
     */
    val zoneNote: String? = null,
    /** A scoring interpreter's raw numbers behind [confidence] (the margin and the winner's score), shown in the value's notes; it changes nothing. */
    val scoreNote: String? = null,
)

/** One value slot as the model wrote it, never checked yet. */
data class RawSlot(
    /** A candidate id, or a quoted name for name slots, or the action enum. */
    val id: String? = null,
    val role: String? = null,
    /** A relative deadline the model quoted from the letter. */
    val rule: String? = null,
    val ids: List<String> = emptyList(),
    val confidence: String? = null,
    /** Like [RawParty.zoneNote]: the answer contradicts the zone's hint. */
    val zoneNote: String? = null,
    /** Like [RawParty.scoreNote]. */
    val scoreNote: String? = null,
)

/**
 * One open-metadata entry: something meaningful that no fixed slot covers.
 *
 * @property label as printed on the page, in the letter's language.
 * @property key a short snake_case English suggestion (metadata, never the identity).
 * @property id a candidate id, or "NONE" when [value] is a quote.
 */
data class RawExtra(
    val label: String,
    val key: String,
    val id: String,
    val value: String,
    val confidence: String? = null,
)

/** Call 1's answer, parsed but not trusted. */
data class RawInterpretation(
    val type: String,
    val typeConfidence: String? = null,
    val language: String?,
    val parties: List<RawParty>,
    val slots: Map<String, RawSlot>,
    val extras: List<RawExtra> = emptyList(),
    /** The answer was cut off at the token limit and closed at its last complete element. */
    val truncated: Boolean = false,
)

/** Call 2's answer, parsed but not trusted. */
data class RawText(
    /** The model's own name for the type when it chose "other", in the letter's language. */
    val otherLabel: String?,
    val title: String?,
    val subject: String?,
    val summary: String?,
    val questions: List<String>,
)

/** What the verifier checks the answer against. */
class VerificationContext(
    val candidates: CandidateSet,
    val offered: OfferedCandidates,
    /** OCR text as normalised for extraction, one entry per page (index 0 is page 1), for verifying quotes. */
    val pageTexts: List<String>,
    val layoutCharsSent: Int,
    val layoutCharsTotal: Int,
    val pagesRead: Int,
    val totalPages: Int,
    val prompt: String? = null,
    val grammar: String? = null,
    val rawAnswer: String? = null,
    val rawText: String? = null,
    val textError: String? = null,
) {
    /** All pages' text, for quotes that may sit anywhere. */
    val ocrText: String by lazy { pageTexts.joinToString("\n") }
}

/** Validation, consistency checks, quote verification and confidence. Never replaces the model's answer. */
interface ResultVerifier {
    /** [text] is null when call 2 was not made or failed; the result then has no free text. */
    fun verify(raw: RawInterpretation, text: RawText?, context: VerificationContext): ExtractionV2Result
}

/** Maps a result onto the field model the app already stores, until workstream E. */
fun interface UnderstandingAdapter {
    fun adapt(result: ExtractionV2Result): DocumentUnderstanding
}
