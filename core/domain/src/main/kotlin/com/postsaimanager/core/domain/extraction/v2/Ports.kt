package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.KeySlot
import com.postsaimanager.core.model.TicketSlot
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.PostalAddress

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
     * ([ExtractionSchema.familiesFor]); incoming until the app stores a direction per document.
     */
    val direction: DocDirection = DocDirection.INCOMING,
    /**
     * The category a person said the document is (a family id; "Change type"): context for every question, as "The user says this document
     * is a <category>.", never a switch that decides what is asked. The category is then not decided again. An id the schema does not know
     * is ignored.
     */
    val forcedFamily: String? = null,
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

    /** Which interpreter this is, for the reading trace. */
    val name: String get() = this::class.simpleName ?: "interpreter"

    /**
     * What the last [interpret] did, as structure only: the layout template, zones, candidate counts, the
     * ids chosen and their scores. Never a word of the letter, so it is safe to log in a debug build.
     */
    val trace: List<String> get() = emptyList()

    /**
     * What the last [interpret] left unread of the letter because it did not fit the window it reads with (an interpreter that
     * renders the letter itself, zone by zone, cuts on its own budget, not the pipeline's); null when it read all of it.
     */
    val unread: UnreadText? get() = null

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

    /**
     * True when [interpret] decides only the type, the parties and the slots (what a person needs to see a result) and
     * [enrich] does the rest (the language, the extras, the free text), so the pipeline can store the first stage at once
     * and finish the second in the background. False (the default): [interpret] and [writeText] are the whole reading.
     */
    val staged: Boolean get() = false

    /**
     * The second stage of a [staged] interpreter: the language, the extras (values no slot or party took) and the free text.
     * Reads the same letter again; an engine that still holds the letter's prefix does not decode it twice.
     */
    suspend fun enrich(request: EnrichmentRequest): EnrichmentOutcome = EnrichmentOutcome.Failed("this interpreter reads everything in one go")
}

/** What the second stage is given: the letter as the first stage had it, and what the first stage decided. */
class EnrichmentRequest(
    val offered: OfferedCandidates,
    val layout: LetterLayout?,
    val pageAspect: Float? = null,
    val direction: DocDirection = DocDirection.INCOMING,
    /** Candidate ids the first stage's slots and parties took: never offered as extras. */
    val takenIds: Set<String> = emptySet(),
    /** The document type the first stage chose, so the texts fit it. */
    val documentTypeId: String? = null,
    /** What the first stage established (see [RawInterpretation.established]); empty when it established nothing. */
    val established: String = "",
    /** The topics the first stage found (empty when it left them to the second stage, or found none). */
    val topics: List<String> = emptyList(),
    /** The verified values the summary is built from (see [com.postsaimanager.core.model.EnrichmentTicket.facts]). */
    val facts: Map<String, String> = emptyMap(),
    /** The letter's text as the verifier reads it, the reference the summary's numbers and names are checked against. */
    val ocrText: String = "",
    /** The fixed slot values the first stage stored, scored for whether the reader needs them (see [Enrichment.keySlots]). */
    val slots: List<TicketSlot> = emptyList(),
    /** The category a person said the document is (a family id): context for every question, and the category is then not decided. */
    val userFamily: String? = null,
)

/**
 * What the second stage wrote, parsed but not trusted; any part may be missing.
 *
 * @property type the family id of the category the stage decided from what was read (the abstain family when none passed), or the person's
 *   own category when one was given; null when the stage could not decide, so the stored type stays.
 * @property typeConfidence the confidence word of [type].
 * @property name the specific name written for the document, in its language, checked against the letter ([DocumentNameVerifier]); null when none was
 *   written or it was not grounded.
 * @property summary the summary the writer settled on (the model's sentences, or the template that renders from the verified
 *   fields); null when the second stage could not write at all, so the summary stays pending.
 * @property topics the topics, when this stage scored them (a profile that keeps them out of the first stage); null otherwise.
 * @property actions the actions chosen by score (what the reader must do: a kind and the stored fields it rests on); empty when the letter
 *   asks nothing; null when none could be scored, so a stored list stays.
 */
class Enrichment(
    val language: String?,
    val extras: List<RawExtra>,
    val text: RawText?,
    val textError: String? = null,
    val rawText: String? = null,
    val summary: SummaryResult? = null,
    val topics: List<String>? = null,
    val actions: List<ActionItem>? = null,
    val keySlots: List<KeySlot>? = null,
    val type: String? = null,
    val typeConfidence: String? = null,
    val name: String? = null,
)

sealed interface EnrichmentOutcome {
    class Done(val enrichment: Enrichment) : EnrichmentOutcome

    class Failed(val reason: String) : EnrichmentOutcome
}

/** Text an interpreter left out to fit: how many lines, and the first page that lost some. */
class UnreadText(val lines: Int, val firstCutPage: Int)

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
    /** The runner-up names of the question, best first (a scoring interpreter); the verifier turns them into the value's alternatives. */
    val alternatives: List<RawAlternative> = emptyList(),
)

/** A candidate the question scored below its winner: the id and the score, for the Edit sheet's chips. */
data class RawAlternative(val id: String, val score: Double)

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
    /** Like [RawParty.alternatives]. */
    val alternatives: List<RawAlternative> = emptyList(),
    /**
     * What the value MEANS ([ValueMeaning.id]) when the reading asked and a meaning beat its content-free baseline; null when it did not
     * ask or the answer is "other". Attached to the slot's own decision, never a second decision: the slot, its [role] and its checks
     * are unchanged.
     */
    val meaning: String? = null,
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
    /** What a scoring interpreter's first stage told its second ("sender: M1 «...»; addressee: ..."): the second stage reads the letter under it. */
    val established: String = "",
    /** The topic ids the reading found, best first (a scoring interpreter); the verifier keeps those the schema knows. */
    val topics: List<String> = emptyList(),
    /** The layout template the letter matched (a zone interpreter); null for one that reads no layout. */
    val layoutTemplate: String? = null,
    /** The structured postal address of the addressee and of the sender (a scoring interpreter, for a family with a recipient block). */
    val addresses: Map<PartyRole, PostalAddress> = emptyMap(),
    /** The sender's other address candidates, best first (see `AddressReading.senderAlternatives`). */
    val senderAddressAlternatives: List<PostalAddress> = emptyList(),
    /**
     * The slots were asked of every document, whatever its type (a scoring interpreter reads before the type is decided, and the type is only
     * a label): the verifier accepts any slot of the schema, instead of those of [type]. False for an interpreter that asked only the slots of
     * the type it chose, where an answer for another type's slot is a mistake.
     */
    val universalSlots: Boolean = false,
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
