package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.model.SamplingConfig

/**
 * What a generation is for, which decides how it is sampled ([samplingFor]).
 *
 * - [STRUCTURED]: the answer is constrained (a JSON schema, a fixed note format) and the model has nothing to be creative about: the
 *   reader's JSON turn, the text step, the follow-up questions, the session notes.
 * - [FREE_TEXT]: prose a person reads (chat replies, the summary): the model's own or the user's sampling.
 */
enum class SamplingPurpose { STRUCTURED, FREE_TEXT }

/**
 * The one owner of "how is this call sampled". A constrained answer is decoded greedily: top-k 1 picks the most likely token every
 * time, so nothing depends on the random draw (on a GPU the logits are half precision, and a draw over a long enum of near-equal
 * choices drifted: plan 19 Q6). The temperature stays a small positive number rather than 0, so no backend divides by it.
 *
 * Free text keeps the app's usual sampling ([SamplingConfig] defaults, which the chat overrides with the model's and the user's own).
 */
fun samplingFor(purpose: SamplingPurpose): SamplingConfig = when (purpose) {
    SamplingPurpose.STRUCTURED -> GREEDY
    SamplingPurpose.FREE_TEXT -> SamplingConfig()
}

private val GREEDY = SamplingConfig(temperature = 0.1f, topK = 1, topP = 1f)
