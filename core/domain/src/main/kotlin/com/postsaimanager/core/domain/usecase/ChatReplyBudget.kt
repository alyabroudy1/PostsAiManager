package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.ThinkingEffort

/**
 * Token budget and sampling for one chat reply. Pure, so it is unit-tested without an engine.
 *
 * ### Budget
 *
 * The reply cap is `thinking budget + [ANSWER_TOKENS]`, so the answer is never squeezed by
 * the reasoning that precedes it (before this, High thinking spent 768 of a flat 1024 tokens
 * on reasoning and cut the answer mid-sentence). The cap is bounded by the context window
 * (half of it), and the thinking budget is then reduced until the answer still keeps at least
 * [MIN_ANSWER_TOKENS]. `llama_jni.cpp` applies the same floor natively against what is
 * actually left in the window (`kMinAnswerReserveTokens`).
 *
 * ### Sampling
 *
 * Chat uses the sampling Qwen3.5-0.8B's model card recommends for text tasks (non-thinking:
 * temperature 1.0, top_p 1.0, top_k 20, presence_penalty 2.0; thinking: temperature 1.0,
 * top_p 0.95, top_k 20, presence_penalty 1.5). Extraction keeps its own low temperature and
 * does not go through here. Note the presence penalty only sees tokens generated in the
 * current reply, not the prompt, so it discourages loops within a reply but cannot by itself
 * stop the model copying an earlier turn.
 */
object ChatReplyBudget {

    const val ANSWER_TOKENS = 768
    const val MIN_ANSWER_TOKENS = 512
    const val BASE_CAP = 1024

    const val THINKING_BUDGET_LOW = 256
    const val THINKING_BUDGET_HIGH = 1024

    /** @param maxTokens the reply cap; @param thinkingBudgetTokens 0 when thinking is off. */
    data class Budget(val maxTokens: Int, val thinkingBudgetTokens: Int) {
        val answerTokens: Int get() = maxTokens - thinkingBudgetTokens
    }

    fun forEffort(effort: ThinkingEffort, contextTokens: Int): Budget {
        val requestedThinking = when (effort) {
            ThinkingEffort.OFF -> 0
            ThinkingEffort.LOW -> THINKING_BUDGET_LOW
            ThinkingEffort.HIGH -> THINKING_BUDGET_HIGH
        }
        // Never below [BASE_CAP] (thinking Off keeps the long-standing 1024), never above half
        // the window.
        val cap = minOf(
            maxOf(requestedThinking + ANSWER_TOKENS, BASE_CAP),
            (contextTokens / 2).coerceAtLeast(MIN_ANSWER_TOKENS + 1),
        )
        if (requestedThinking == 0) return Budget(cap, 0)
        val thinking = minOf(requestedThinking, cap - MIN_ANSWER_TOKENS).coerceAtLeast(1)
        return Budget(cap, thinking)
    }

    /** Sampling parameters for a chat turn in [effort]'s mode, as the request carries them. */
    fun sampling(effort: ThinkingEffort): Sampling =
        if (effort == ThinkingEffort.OFF) {
            Sampling(temperature = 1.0f, topP = 1.0f, topK = 20, presencePenalty = 2.0f)
        } else {
            Sampling(temperature = 1.0f, topP = 0.95f, topK = 20, presencePenalty = 1.5f)
        }

    data class Sampling(val temperature: Float, val topP: Float, val topK: Int, val presencePenalty: Float)

    fun request(effort: ThinkingEffort, contextTokens: Int): AiRequest {
        val budget = forEffort(effort, contextTokens)
        val sampling = sampling(effort)
        return AiRequest(
            prompt = "",
            maxTokens = budget.maxTokens,
            temperature = sampling.temperature,
            topK = sampling.topK,
            topP = sampling.topP,
            presencePenalty = sampling.presencePenalty,
            thinkingEnabled = effort != ThinkingEffort.OFF,
            thinkingBudgetTokens = budget.thinkingBudgetTokens,
        )
    }
}
