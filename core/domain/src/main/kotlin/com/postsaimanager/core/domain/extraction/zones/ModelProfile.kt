package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.address.LineAsk
import com.postsaimanager.core.domain.extraction.v2.DocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.InterpreterFactory
import com.postsaimanager.core.domain.extraction.v2.ModelDocumentInterpreter
import com.postsaimanager.core.domain.extraction.v2.QuestionnaireInterpreter
import javax.inject.Inject

/** How a model reads a letter. Every strategy produces the same `RawInterpretation`; the rest of the pipeline is unchanged. */
enum class InterpreterStrategy {
    /** One big grammar-constrained JSON, then the free text ([ModelDocumentInterpreter]). */
    SINGLE,

    /** The whole letter and its candidate table read once, then many short multiple-choice questions ([QuestionnaireInterpreter]). */
    QUESTIONNAIRE,

    /** Layout template, then each question on its own zone with only that zone's candidates ([ZoneInterpreter]). */
    ZONES,

    /** Layout template, then a yes/no log-odds score per candidate, no option labels ([ZoneScoringInterpreter]). */
    ZONES_SCORING,
}

/**
 * What is known about one catalogue model for reading letters, as data: the window to budget the letter
 * against, the strategy that reads best with it, and the knobs of that strategy. The strategy is chosen
 * per model because small and large models fail differently (a small one prefers the first option of a list;
 * a large one does not need the help).
 *
 * @property restateOptions QUESTIONNAIRE only: restate the candidates a question chooses from after it.
 * @property scoring ZONES_SCORING only: the abstain thresholds and confidence margins for this model.
 * @property topicsInFirstStage ZONES_SCORING only: the topic scores run in the first stage with the family scores (+14 scores); false
 *   moves them to the second stage, when the first stage's time budget is exceeded.
 */
data class ModelProfile(
    val modelId: String,
    val contextTokens: Int,
    val strategy: InterpreterStrategy,
    val restateOptions: Boolean = false,
    val scoring: ScoringProfile = ScoringProfile(),
    val topicsInFirstStage: Boolean = true,
)

/** The registry of profiles, keyed by catalogue model id. */
object ModelProfiles {

    /**
     * Chosen by the on-device benchmark on the 16 real-OCR letters (Experiment Z, real Qwen3.5-0.8B):
     * ZONES_SCORING reads 56% of the facts at t=0 and 68% with cross-fitted thresholds, names the sender and
     * the addressee right in 87% of letters and hallucinates nothing, against the questionnaire's 64%, 40% and
     * 12%; the generated zone answers (ZONES) were worse than both (45%, 13%, 13%).
     *
     * The abstain threshold is effectively off (-12): tuned on either half of the letters and tested on the other,
     * the best setting for almost every question was "always take the best candidate", and the two thresholds that
     * moved (cited_references, recipient_org) rest on one or two answers.
     */
    val QWEN35_08B = ModelProfile(
        "qwen3.5-0.8b-q4_k_m", contextTokens = 4096, strategy = InterpreterStrategy.ZONES_SCORING,
        // The +14 topic scores run with the family scores; P4 measures the time and flips this when it is over budget.
        topicsInFirstStage = true,
        scoring = ScoringProfile(
            defaultThreshold = -12.0,
            // The extras are the one question where "take the best" is wrong: a value is an extra only when the model says yes to it.
            // The optional people (a contact, a care-of party, the person a letter is about) are taken only when the model leans Yes:
            // on the phone a table header was taken as a subject person at a score of -0.35 and proposed as a profile.
            thresholds = mapOf(
                ScoringDescriptions.EXTRAS_ASK to 0.0,
                QuestionNames.CONTACT to 0.0, QuestionNames.CARE_OF to 0.0, QuestionNames.SUBJECT_PERSON to 0.0,
                // The family: abstain (free_form) when no family's log-odds are above 0.0, the model's own indifference between Yes and No.
                // On the recorded type scores read through LegacyTypes (FamilyAccuracyTest, 13 letters) every threshold from -0.1 to 0.3
                // gives 11 right (10 at or below -0.25, 9 from 0.4); 0.0 is taken from the plateau, not tuned to a letter: it turns the
                // N4 offer (best score -0.14) into the abstain the letter honestly is. In-sample and on 13 letters, so it is a starting
                // point that P4 refits on the family scores re-recorded on the device.
                // Refit on the device recordings of extraction-v2-2 (`zonesscoring3`, P4ThresholdFitTest, artifacts17/threshold-fit.md):
                // - family: on the 13 letters the best plateau runs from -1.0 to 0.3 (9 right at every point; 0.4 and above is worse), the
                //   leave-one-out is 9 as well, so 0.0 stays: inside the plateau, and the model's own indifference.
                // - topics: micro F1 against the manifests' topics is 0.43 at 0.0 and 0.48 at the plateau middle 0.1, but the cross-fitted
                //   (leave-one-out) F1 of the fitted threshold is 0.34, below the 0.43 of the fixed 0.0: the fit gains nothing out of sample, so 0.0 stays.
                // - address labels: 10 scored word-only lines, 2 with a manifest truth label (both argmax right): too few to fit, 0.0 stays;
                //   an unlabelled line stays a raw line, which is the safe side.
                // - delivery points: the highest score the model gave a street-shaped line for a box or a locker is -0.32 (no letter has either),
                //   so 0.0 is above every score and there is no false positive.
                ScoringProfile.FAMILY to 0.0, ScoringProfile.TOPICS to 0.0,
                // An address line takes a label only when its best label scores above this (LineAsk.LABEL_ASK).
                LineAsk.LABEL_ASK to 0.0,
                // A street-shaped address line is a post office box or a locker only when the model leans Yes.
                LineAsk.DELIVERY_ASK to 0.0,
            ),
            // Fitted on the 137 scored answers of the 16 letters (ConfidenceCalibrationTest). HIGH: a margin of 0.2 over the runner-up
            // (91% right in-sample, 85 answers; 83% held out, cuts fitted on the other half of the letters). LOW: the winner's own
            // log-odds are under -0.25, the model itself leaning No (50% right, 14 answers in-sample). The fitter, which keeps a safety
            // buffer under the 50% ceiling, found no LOW set of its own (held out, its best was 63% right on 8 answers), so this LOW cut
            // was chosen from the table, not by the fitter. MEDIUM is the rest (66%).
            cuts = ScoreCuts(mediumMargin = Double.NEGATIVE_INFINITY, mediumBest = -0.25, highMargin = 0.2, highBest = Double.NEGATIVE_INFINITY),
            // Experiment E1 (DecoderEvalTest, recorded scores of the 16 letters): one value answers one question, so a question's
            // best candidate goes to the question that needs it more. Field match 70.3% -> 74.7% (three letters: a reminder's fee
            // and total, a reference and a customer number, a customer number), roles unchanged at 86.7%, hallucination 0%. The
            // plain joint assignment has no number to fit; every variant with a tuned weight or a calibration did no better
            // held out (the date-order penalty and the net + VAT = gross bonus tuned to nothing or hurt one letter).
            decoder = DecoderSpec(DecoderKind.JOINT),
        ),
    )

    /**
     * What a device run records with: [shipped] without the abstain thresholds of the questions whose decision nothing else depends on (the
     * family and the topics, the extras, the address labels and delivery points: a threshold can be fitted offline on recorded scores of
     * every candidate). The thresholds of the optional people (a contact, a care-of party, a subject person) stay: whether they are taken
     * decides what the other values may take, the address lines the parties settle and the facts of the summary, so the recording must
     * make the decisions the shipped profile makes for the questions it holds to be found again on replay. The decoder is the shipped one.
     */
    fun recordingProfile(shipped: ScoringProfile): ScoringProfile = shipped.copy(
        thresholds = shipped.thresholds.filterKeys { it in PARTY_THRESHOLDS },
    )

    private val PARTY_THRESHOLDS = setOf(QuestionNames.CONTACT, QuestionNames.CARE_OF, QuestionNames.SUBJECT_PERSON)

    val QWEN35_2B = ModelProfile("qwen3.5-2b-q4_k_m", contextTokens = 4096, strategy = InterpreterStrategy.ZONES)

    val QWEN35_4B = ModelProfile("qwen3.5-4b-q4_k_m", contextTokens = 6144, strategy = InterpreterStrategy.QUESTIONNAIRE)

    val GEMMA4_E2B = ModelProfile("gemma-4-e2b-it-qat-q4_0", contextTokens = 4096, strategy = InterpreterStrategy.ZONES)

    val GEMMA4_E4B = ModelProfile("gemma-4-e4b-it-qat-q4_0", contextTokens = 6144, strategy = InterpreterStrategy.QUESTIONNAIRE)

    /** For a model with no profile: the strategy that needs no prior measurement. */
    val FALLBACK = ModelProfile("", contextTokens = 4096, strategy = InterpreterStrategy.SINGLE)

    val ALL: List<ModelProfile> = listOf(QWEN35_08B, QWEN35_2B, QWEN35_4B, GEMMA4_E2B, GEMMA4_E4B)

    /** The profile of [modelId] (matched ignoring case and surrounding blanks), or [FALLBACK] for a model with none. */
    fun of(modelId: String?): ModelProfile = find(modelId) ?: FALLBACK

    /** Whether [modelId] has a registered profile; a model without one reads with the fallback strategy and is reported in the reading trace. */
    fun isKnown(modelId: String?): Boolean = find(modelId) != null

    private fun find(modelId: String?): ModelProfile? {
        val key = modelId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALL.firstOrNull { it.modelId.lowercase() == key }
    }
}

/**
 * The [InterpreterFactory] the app binds: picks the interpreter from the extraction model's [ModelProfile],
 * looked up by its catalogue id (an unknown model gets [ModelProfiles.FALLBACK], the single call).
 */
class ProfileInterpreterFactory @Inject constructor(
    private val engine: AiEngine,
    private val session: PromptSession,
) : InterpreterFactory {

    override fun create(contextTokens: Int, modelId: String?): DocumentInterpreter = create(contextTokens, ModelProfiles.of(modelId))

    fun create(contextTokens: Int, profile: ModelProfile): DocumentInterpreter {
        // The profile's window is the one its strategy was measured with: never budget the letter beyond it.
        val window = minOf(contextTokens, profile.contextTokens)
        return when (profile.strategy) {
            InterpreterStrategy.SINGLE -> ModelDocumentInterpreter(engine, contextTokens = window)
            InterpreterStrategy.QUESTIONNAIRE ->
                QuestionnaireInterpreter(engine, session, contextTokens = window, restateOptions = profile.restateOptions)
            InterpreterStrategy.ZONES -> ZoneInterpreter(engine, session, contextTokens = window)
            InterpreterStrategy.ZONES_SCORING ->
                ZoneScoringInterpreter(engine, session, contextTokens = window, profile = profile.scoring, topicsInFirstStage = profile.topicsInFirstStage)
        }
    }
}
