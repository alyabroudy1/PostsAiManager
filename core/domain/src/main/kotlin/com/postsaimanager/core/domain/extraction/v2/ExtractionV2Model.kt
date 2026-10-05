package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.Validation
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.FieldAlternative
import com.postsaimanager.core.model.KeySlot
import com.postsaimanager.core.model.PostalAddress
import com.postsaimanager.core.model.TextBounds
import java.time.LocalDate

/**
 * The output of extraction v2, "AI decides, code verifies".
 *
 * Code finds value-shaped things (dates, amounts, IBANs, references, name-like lines) and checks
 * them. The model decides what every one of them *means*: the document type, which amount is the
 * total, which date is the due date, who sent the letter and who it is for. Nothing in this
 * package looks at a German keyword to make such a decision, so the same path serves a German, an
 * English and an Arabic letter.
 */

/** Where a value came from. Nothing is assigned by rules, so there is no deterministic origin. */
enum class SlotOrigin {
    /** The model picked a candidate id that code found in the OCR text. */
    MODEL_CHOICE,

    /** The model quoted text and code found that quote in the OCR text. Confidence is capped. */
    MODEL_QUOTED,

    /** Text the model wrote itself (title, an unverifiable summary). Shown as "AI"; never trusted as fact. */
    MODEL_GENERATED,
}

/** How closely a quote matched the OCR text. */
enum class QuoteMatch { EXACT, NORMALIZED, FUZZY }

/**
 * One value the model chose or quoted, with everything needed to cite it.
 *
 * @property candidateId the candidate the model picked, or null for a quote or an enum answer.
 * @property role what the model said the value is (amount or date role enum), when it said.
 * @property aiConfidence what the model said about its own answer (LOW 0.4, MEDIUM 0.7, HIGH 0.9),
 *   kept unchanged so the benchmark can calibrate the model's self-reports against real accuracy.
 * @property confidence the confidence to show: [aiConfidence] capped by the code's checks, never
 *   raised by them (see [ConfidenceCombiner]).
 * @property blocked a check failed that the user should look at (validation, a role mismatch, a
 *   conflict, a fuzzy quote). The model's answer is kept, never replaced.
 * @property needsReview [blocked], or [confidence] below [ConfidenceCombiner.REVIEW_BELOW].
 * @property notes the checks that capped the confidence, in words, for diagnostics and the benchmark.
 * @property idLogProb reserved for a later export of the sampler's log-probability of the chosen id
 *   (a second, model-internal confidence signal). Always null today.
 * @property alternatives the runner-up readings of the question (a scoring interpreter), best first, each a value the letter offered
 *   that the model scored below the chosen one; the Edit sheet's chips.
 */
data class SlotValue(
    val slot: SlotKey?,
    val candidateId: String?,
    val value: String,
    val normalized: String,
    val page: Int?,
    val bbox: TextBounds?,
    val evidence: String,
    val origin: SlotOrigin,
    val aiConfidence: Float,
    val confidence: Float,
    val validation: Validation,
    val blocked: Boolean = false,
    val notes: List<String> = emptyList(),
    val quoteMatch: QuoteMatch? = null,
    val role: String? = null,
    val idLogProb: Double? = null,
    val alternatives: List<FieldAlternative> = emptyList(),
) {
    val needsReview: Boolean get() = blocked || confidence < ConfidenceCombiner.REVIEW_BELOW
}

enum class PartyRole { SENDER, ADDRESSEE, CO_ADDRESSEE, ROUTING, CARE_OF, SUBJECT_PERSON }

enum class PartyKind { PERSON, AUTHORITY, COMPANY, OTHER }

/** How a party relates to the letter beyond its role: a household is addressed, a guardian stands for the child. */
enum class PartyRelation { NONE, GUARDIAN_OF, HOUSEHOLD }

/**
 * One party as the model decided and code verified.
 *
 * @property value the name as printed (from a candidate) or as quoted, with its checks.
 */
data class Party(
    val role: PartyRole,
    val kind: PartyKind,
    val relation: PartyRelation,
    val value: SlotValue,
) {
    val name: String get() = value.value
}

/** Who is who. The accessors are the roles the app cares about. */
data class Parties(val all: List<Party> = emptyList()) {
    fun withRole(role: PartyRole): List<Party> = all.filter { it.role == role }

    val sender: Party? get() = withRole(PartyRole.SENDER).firstOrNull()
    val addressees: List<Party> get() = withRole(PartyRole.ADDRESSEE)
    val coAddressees: List<Party> get() = withRole(PartyRole.CO_ADDRESSEE)

    /** The addressee and the co-addressees together, in the order the model gave them. */
    val allAddressees: List<Party> get() = all.filter { it.role == PartyRole.ADDRESSEE || it.role == PartyRole.CO_ADDRESSEE }
    val routingPerson: Party? get() = withRole(PartyRole.ROUTING).firstOrNull()
    val careOf: Party? get() = withRole(PartyRole.CARE_OF).firstOrNull()
    val subjectPersons: List<Party> get() = withRole(PartyRole.SUBJECT_PERSON)

    /** A household is addressed, or a guardian is addressed on behalf of someone else. */
    val household: Boolean
        get() = allAddressees.any { it.relation == PartyRelation.HOUSEHOLD || it.relation == PartyRelation.GUARDIAN_OF }
}

/**
 * Open metadata: something meaningful the model found that no fixed slot covers.
 *
 * @property label as printed on the page, in the letter's language; shown to the user unchanged.
 * @property key the model's short snake_case English suggestion (`meter_number`, `tariff`). Metadata
 *   for grouping and search only: it changes from run to run, so it is never the identity.
 * @property identity what a stored field is matched by across re-reads: `x:` plus the printed
 *   label, folded and with spaces as underscores. A label is stable in the letter; the key is not.
 */
data class ExtraValue(val label: String, val key: String, val value: SlotValue) {
    val identity: String get() = identityOf(label)

    companion object {
        fun identityOf(label: String): String =
            "x:" + QuoteVerifier.fold(label).replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_')
    }
}

/** The free text the model wrote, each with the origin that says how far to trust it. */
data class FreeText(
    /** The letter's subject line as printed. A verified quote, or dropped. */
    val subject: SlotValue? = null,
    /** One or two sentences. [SlotOrigin.MODEL_QUOTED] when every sentence was found in the text, else [SlotOrigin.MODEL_GENERATED]. */
    val summary: SlotValue? = null,
    /** At most eight words in the letter's language: sender and purpose. Generated, never a fact. */
    val title: SlotValue? = null,
    /** Three questions a reader might ask, in the letter's language. */
    val suggestedQuestions: List<String> = emptyList(),
)

data class Diagnostics(
    val candidateCount: Int,
    val offeredCount: Int,
    /** Candidates found but not offered because a kind was over its cap, per kind name. */
    val offeredDropped: Map<String, Int> = emptyMap(),
    val modelCalled: Boolean,
    /** True when the model's structured answer was parsed and used. False with [modelCalled] means it failed. */
    val modelUsed: Boolean,
    val modelError: String? = null,
    /** Why the free-text call gave nothing, when it was made and failed. */
    val textError: String? = null,
    val rawAnswer: String? = null,
    val rawText: String? = null,
    /** The structured answer hit the token cap and was closed at its last complete element; its confidences are capped. */
    val truncated: Boolean = false,
    /** Answers that were dropped: an id outside the offered set, an id of the wrong kind, a quote that is not in the text. */
    val rejections: List<String> = emptyList(),
    /** Role conflicts and failed consistency checks. Each forces the affected values to needs-review. */
    val conflicts: List<String> = emptyList(),
    val layoutCharsSent: Int = 0,
    val layoutCharsTotal: Int = 0,
    val pagesRead: Int = 0,
    val totalPages: Int = 0,
    /** The structured call's grammar and prompt, for tests and the benchmark. Never part of the result proper. */
    val grammar: String? = null,
    val prompt: String? = null,
    /** The reading's structure, no letter text (see [DocumentInterpreter.trace]); the pipeline adds the layout's own lines. */
    val trace: List<String> = emptyList(),
    /** Lines the interpreter left out to fit its own window, beyond what the layout text cut; 0 when it read everything. */
    val unreadLines: Int = 0,
) {
    val layoutComplete: Boolean get() = layoutCharsSent >= layoutCharsTotal && unreadLines == 0
}

/**
 * The result of extraction v2.
 *
 * @property documentType the model's choice, null without a model.
 * @property otherLabel the model's own name for the type when it chose "other", in the letter's language.
 * @property foundValues without a model, or when the model's answer was unusable: the values code
 *   found, with no role, no type and no guess. The adapter shows them as "found" fields marked
 *   for review.
 */
data class ExtractionV2Result(
    val documentType: DocFamily?,
    /** The model's own confidence in the type; no code check can raise or lower it. */
    val typeConfidence: Float = 0f,
    val otherLabel: String? = null,
    val language: String?,
    val slots: Map<SlotKey, SlotValue>,
    val slotLists: Map<SlotKey, List<SlotValue>> = emptyMap(),
    val parties: Parties = Parties(),
    /**
     * The structured postal address of the addressee ([PartyRole.ADDRESSEE]) and of the sender ([PartyRole.SENDER]), when the letter
     * prints one and the reading stage ran (see `StructuredAddressReader`, called by the scoring interpreter for a family with a recipient
     * block). Empty for any other reading.
     */
    val addresses: Map<PartyRole, PostalAddress> = emptyMap(),
    /** The sender's other address candidates (letterhead, return line, footer that did not win), best first. */
    val senderAddressAlternatives: List<PostalAddress> = emptyList(),
    /** The topics the reading found and the schema knows, best first. */
    val topics: List<String> = emptyList(),
    /** The layout template the letter matched; null when the interpreter read no layout. */
    val layoutTemplate: String? = null,
    /** The summary as the writer settled on it (the model's sentences or the template); null when none was written (a first stage, or a failed second one). */
    val summary: SummaryResult? = null,
    /** The action lines the second stage kept (what the reader must do); empty when it found none; null when none was asked. */
    val actions: List<String>? = null,
    /** The stored slots the second stage picked as key information, best first; null when it did not score them. */
    val keySlots: List<KeySlot>? = null,
    /** The title composed from the family, the sender and the verified subject; null when nothing could be composed (no model read the letter). */
    val composedTitle: TitleComposer.Composed? = null,
    val freeText: FreeText = FreeText(),
    /** Verified open metadata, at most [StructuredGrammar.MAX_EXTRAS]. */
    val extras: List<ExtraValue> = emptyList(),
    val foundValues: List<Candidate> = emptyList(),
    val letterDate: LocalDate? = null,
    val diagnostics: Diagnostics,
    /** Set on a first-stage result that a second stage is to complete (see [ExtractionV2Pipeline.Stages]); null otherwise. */
    val enrichment: EnrichmentTicket? = null,
) {
    /**
     * Something in the result failed a check the user should look at: no model, a conflict, or a
     * value a check blocked. A merely medium-confidence value does not count here; it is flagged on
     * the value itself ([SlotValue.needsReview]).
     */
    val needsReview: Boolean
        get() = !diagnostics.modelUsed ||
            diagnostics.conflicts.isNotEmpty() ||
            slots.values.any { it.blocked } ||
            parties.all.any { it.value.blocked }
}
