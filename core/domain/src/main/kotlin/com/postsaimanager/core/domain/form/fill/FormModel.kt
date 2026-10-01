package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.FormScorer
import com.postsaimanager.core.domain.form.FormScoringException
import com.postsaimanager.core.domain.form.PromptFraming
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the form conversation asks of the on-device model, in two shapes only: label-free yes/no SCORES (the model decides what
 * a message means) and a short free TEXT (the model words a question). Neither ever yields a value for a field: values come
 * from profiles, verified answers and printed options, in code.
 *
 * A port so the conversation is testable without a model; [EngineFormModel] binds it to the loaded chat model.
 */
interface FormModel {

    /** Loads the active chat model; an error when none is installed or it fails to load. */
    suspend fun ensureLoaded(): PamResult<Unit>

    /**
     * For each of [statements], the log-odds that it is true of [context] (read by a model told [system]); higher is more
     * likely. The model reads [context] once, whatever the number of statements.
     */
    suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>>

    /** One short line the model writes after reading [user] (told [system]), at most [maxTokens]; null when it cannot. */
    suspend fun write(system: String, user: String, maxTokens: Int): String?
}

/** [FormModel] over the standing engine: the same loaded model and the same [PromptSession] the form understanding uses. */
class EngineFormModel @Inject constructor(
    private val engine: AiEngine,
    private val activeModels: ActiveModelProvider,
    private val session: PromptSession,
    private val framing: PromptFraming,
) : FormModel {

    override suspend fun ensureLoaded(): PamResult<Unit> {
        val path = activeModels.activeModelPath() ?: return PamResult.Error(PamError.ModelNotLoaded("chat"))
        return when (val loaded = engine.load(path, activeModels.activeModelConfig())) {
            is PamResult.Error -> loaded
            is PamResult.Success -> PamResult.Success(Unit)
        }
    }

    override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> {
        if (statements.isEmpty()) return PamResult.Success(emptyList())
        val (head, tail) = framing.frame(system, context)
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        return try {
            PamResult.Success(FormScorer(session, tail).yesNo(statements))
        } catch (e: FormScoringException) {
            PamResult.Error(e.error)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    override suspend fun write(system: String, user: String, maxTokens: Int): String? {
        val (head, tail) = framing.frame(system, user)
        if (session.open(head) is PamResult.Error) return null
        return try {
            (session.ask(withoutThinking(tail), ONE_LINE, maxTokens) as? PamResult.Success)?.data
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    /**
     * A reasoning model's generation prompt can end inside an open `<think>` block, and a one-line question would then be spent
     * on reasoning (the "question" was the model's chain of thought, cut off). The block is closed empty, so the first generated
     * token is the question itself.
     */
    private fun withoutThinking(tail: String): String = closeThinking(tail)

    internal companion object {
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"

        /**
         * The generation prompt [tail] with an empty, closed reasoning block, as the chat does for a turn without thinking. The
         * on-device template ends at the bare assistant tag (it cannot pass the thinking flag), and a reasoning model then opens
         * `<think>` itself: the one-line grammar stopped at its first newline, so the "question" was just `<think>`. Closing the
         * block first makes the model's first token the question.
         */
        fun closeThinking(tail: String): String {
            val trimmed = tail.trimEnd()
            return when {
                trimmed.endsWith(THINK_CLOSE) -> tail
                trimmed.endsWith(THINK_OPEN) -> "$trimmed\n\n$THINK_CLOSE\n\n"
                else -> "$tail$THINK_OPEN\n\n$THINK_CLOSE\n\n"
            }
        }

        /** One line of free text: no newline, at least a few characters. */
        const val ONE_LINE = "root ::= [^\\n]{4,200}\n"
    }
}
