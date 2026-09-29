package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.coroutines.flow.toList
import kotlin.coroutines.cancellation.CancellationException

/**
 * The two model calls per document, through the [AiEngine] port and nothing else.
 *
 * Builds the prompts and the grammars (all pure, all tested on their own), asks with greedy
 * sampling, and parses the answers. It does not judge them: that is the verifier's job.
 *
 * Sampling is the extraction path's own, not chat's: temperature 0 (greedy), so the same letter
 * gives the same reading on two runs and the merge never reports sampling noise as a change. No
 * thinking, and each grammar keeps the reply to one JSON object.
 *
 * The engine's `generate` starts from an empty KV cache, so the two calls do not share a prefix and
 * the letter is prefilled twice. That is accepted: one call with the letter, the candidate table and
 * both answers does not fit the 4K window.
 */
class ModelDocumentInterpreter(
    private val engine: AiEngine,
    private val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    contextTokens: Int,
) : DocumentInterpreter {

    private val withExample = contextTokens >= SelectionPrompt.EXAMPLE_MIN_CONTEXT_TOKENS

    override val maxAnswerTokens: Int = MAX_ANSWER_TOKENS
    override val maxTextTokens: Int = MAX_TEXT_TOKENS

    override fun promptOverheadChars(offered: OfferedCandidates): Int =
        SelectionPrompt.overheadChars(schema, offered, withExample)

    override fun textOverheadChars(): Int = SelectionPrompt.textOverheadChars()

    override suspend fun interpret(request: InterpretationRequest): InterpretationOutcome {
        val grammar = StructuredGrammar.build(request.offered, schema)
        val prompt = engine.formatPrompt(
            listOf(
                AiChatMessage(AiChatRole.SYSTEM, SelectionPrompt.system(schema, withExample)),
                AiChatMessage(AiChatRole.USER, SelectionPrompt.user(request.layoutText, request.offered)),
            ),
        )
        val raw = generate(prompt, grammar, MAX_ANSWER_TOKENS)
            ?: return InterpretationOutcome.Failed("the model failed", null, prompt, grammar)
        if (raw.isBlank()) return InterpretationOutcome.Failed("the model returned nothing", raw, prompt, grammar)
        return when (val parsed = InterpretationParser.parse(raw)) {
            is InterpretationParser.Parsed.Ok -> InterpretationOutcome.Answered(parsed.value, raw, prompt, grammar)
            is InterpretationParser.Parsed.Bad -> InterpretationOutcome.Failed(parsed.reason, raw.take(300), prompt, grammar)
        }
    }

    override suspend fun writeText(request: TextRequest): TextOutcome {
        val prompt = engine.formatPrompt(
            listOf(
                AiChatMessage(AiChatRole.SYSTEM, SelectionPrompt.TEXT_SYSTEM),
                AiChatMessage(AiChatRole.USER, SelectionPrompt.textUser(request.layoutText, request.documentTypeId)),
            ),
        )
        val raw = generate(prompt, TextGrammar.build(), MAX_TEXT_TOKENS) ?: return TextOutcome.Failed("the model failed")
        if (raw.isBlank()) return TextOutcome.Failed("the model returned nothing")
        return when (val parsed = InterpretationParser.parseText(raw)) {
            is InterpretationParser.Parsed.Ok -> TextOutcome.Written(parsed.value, raw)
            is InterpretationParser.Parsed.Bad -> TextOutcome.Failed(parsed.reason)
        }
    }

    /** The engine's whole answer, or null when it failed. Cancellation is not a failure. */
    private suspend fun generate(prompt: String, grammar: String, maxTokens: Int): String? = try {
        engine.generate(
            AiRequest(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = 0f,
                grammar = grammar,
                thinkingEnabled = false,
            ),
        ).toList().joinToString("")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /**
         * Call 1: type, up to six parties, a dozen slots with confidence, up to six extras. About
         * 350-450 tokens for a typical letter (see the pipeline test that measures it); headroom
         * for a longer one. An answer cut off at this limit is closed at its last complete element
         * (see [InterpretationParser]) and its confidences are capped.
         */
        const val MAX_ANSWER_TOKENS = 640

        /** Call 2: title, subject, summary, three questions. About 150-250 tokens. */
        const val MAX_TEXT_TOKENS = 384
    }
}
