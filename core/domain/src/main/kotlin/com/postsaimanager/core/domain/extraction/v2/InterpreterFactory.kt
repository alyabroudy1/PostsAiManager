package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import javax.inject.Inject

/** How the model reads a letter. Both produce the same [RawInterpretation]; the rest of the pipeline is unchanged. */
enum class InterpreterMode {
    /** One big grammar-constrained JSON for everything, then a second call for the free text ([ModelDocumentInterpreter]). */
    SINGLE_CALL,

    /** The letter read once, then many tiny focused questions ([QuestionnaireInterpreter]). */
    QUESTIONNAIRE,
}

/** Makes the [DocumentInterpreter] for one extraction, sized to the window the model was loaded with. */
fun interface InterpreterFactory {
    fun create(contextTokens: Int): DocumentInterpreter
}

/**
 * The factory the app binds: picks the interpreter by [InterpreterMode]. Kept until the benchmark
 * decides between the two; then the loser and this class go away.
 */
class ModeInterpreterFactory @Inject constructor(
    private val engine: AiEngine,
    private val promptSession: PromptSession,
    private val mode: InterpreterMode,
) : InterpreterFactory {

    override fun create(contextTokens: Int): DocumentInterpreter = when (mode) {
        InterpreterMode.SINGLE_CALL -> ModelDocumentInterpreter(engine, contextTokens = contextTokens)
        InterpreterMode.QUESTIONNAIRE -> QuestionnaireInterpreter(engine, promptSession, contextTokens = contextTokens)
    }
}
