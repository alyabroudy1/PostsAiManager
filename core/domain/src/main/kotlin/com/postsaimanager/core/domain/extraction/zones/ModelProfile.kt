package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
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
 */
data class ModelProfile(
    val modelId: String,
    val contextTokens: Int,
    val strategy: InterpreterStrategy,
    val restateOptions: Boolean = false,
    val scoring: ScoringProfile = ScoringProfile(),
)

/** The registry of profiles, keyed by catalogue model id. */
object ModelProfiles {

    /**
     * The measured default for the smallest catalogue model: see `documentation/` (Experiment Z) for the
     * benchmark that chose it.
     */
    val QWEN35_08B = ModelProfile("qwen3.5-0.8b-q4_k_m", contextTokens = 4096, strategy = InterpreterStrategy.ZONES)

    val QWEN35_2B = ModelProfile("qwen3.5-2b-q4_k_m", contextTokens = 4096, strategy = InterpreterStrategy.ZONES)

    val QWEN35_4B = ModelProfile("qwen3.5-4b-q4_k_m", contextTokens = 6144, strategy = InterpreterStrategy.QUESTIONNAIRE)

    val GEMMA4_E2B = ModelProfile("gemma-4-e2b-it-qat-q4_0", contextTokens = 4096, strategy = InterpreterStrategy.ZONES)

    val GEMMA4_E4B = ModelProfile("gemma-4-e4b-it-qat-q4_0", contextTokens = 6144, strategy = InterpreterStrategy.QUESTIONNAIRE)

    /** For a model with no profile: the strategy that needs no prior measurement. */
    val FALLBACK = ModelProfile("", contextTokens = 4096, strategy = InterpreterStrategy.SINGLE)

    val ALL: List<ModelProfile> = listOf(QWEN35_08B, QWEN35_2B, QWEN35_4B, GEMMA4_E2B, GEMMA4_E4B)

    fun of(modelId: String?): ModelProfile = ALL.firstOrNull { it.modelId == modelId } ?: FALLBACK
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
            InterpreterStrategy.ZONES_SCORING -> ZoneScoringInterpreter(engine, session, contextTokens = window, profile = profile.scoring)
        }
    }
}
