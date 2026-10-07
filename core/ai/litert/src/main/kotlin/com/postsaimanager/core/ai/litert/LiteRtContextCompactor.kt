/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: the decision logic of the Google AI Edge Gallery's agent/sessions/SummarizationContextCompactor.kt
// (v1.0.20) and ContextCompactor.kt: the 75% trigger, the summary prompt and its word limit, the failure back-off, and the summary
// pair the conversation restarts from. Left out: the Gallery's session manager, Model/ConfigKeys/SessionConfig types, Hilt and the
// per-session mutexes (the engine already serialises every call). The model call and the conversation reset are done by
// LiteRtChatEngine on its own live conversation, in the :inference process; this class only decides and shapes.

package com.postsaimanager.core.ai.litert

import kotlin.math.min

/**
 * When a conversation has grown too long, and what it restarts from: the Gallery's summarise-and-reset, as pure decisions.
 *
 * The flow, as in the Gallery: past 75% of the window ([shouldCompact]) the live conversation is asked to summarise itself
 * ([summaryPrompt]); the conversation is then reset with that summary as its first exchange ([turnsAfterSummary]). If the summary
 * fails the check backs off for [FAILURE_BACKOFF_CHECKS] sends ([onFailure]) before it tries again. The summary lives in memory only,
 * as in the Gallery: it is not stored with the chat, so a rebuild after the process died starts from the newest stored turns that fit
 * (`SendChatMessageUseCase.buildHistory`).
 *
 * Unlike the Gallery, which restarts from the summary alone, the newest exchange is kept after it with its tool calls, so the model
 * still has a real tool-call turn to follow.
 */
internal class LiteRtContextCompactor {

    private var backoffChecksRemaining = 0

    /** Whether [usedTokens] of a [windowTokens] window is past the trigger (the Gallery's `isOverTokenThreshold`). */
    fun isOverThreshold(usedTokens: Int, windowTokens: Int): Boolean =
        usedTokens > 0 && windowTokens > 0 && usedTokens > windowTokens * TOKEN_LIMIT_THRESHOLD_RATIO

    /** True when the conversation should be summarised now: over the threshold and not backing off after a failure. */
    fun shouldCompact(usedTokens: Int, windowTokens: Int): Boolean {
        if (!isOverThreshold(usedTokens, windowTokens)) {
            backoffChecksRemaining = 0
            return false
        }
        if (backoffChecksRemaining > 0) {
            backoffChecksRemaining--
            return false
        }
        return true
    }

    fun onSuccess() {
        backoffChecksRemaining = 0
    }

    fun onFailure() {
        backoffChecksRemaining = FAILURE_BACKOFF_CHECKS
    }

    /** The word limit of the summary: what is left of the window, in words, within the Gallery's bounds. */
    fun wordLimit(usedTokens: Int, windowTokens: Int): Int =
        min(((windowTokens - usedTokens) * WORD_TO_TOKEN_RATIO).toInt().coerceAtLeast(MIN_SUMMARY_WORD_LIMIT), MAX_SUMMARY_WORD_LIMIT)

    /** The Gallery's summary request, word for word. */
    fun summaryPrompt(wordLimit: Int): String =
        "Summarize the core points of our conversation so far in less than $wordLimit words, ensuring no important context is lost."

    /**
     * The turns the conversation restarts from: the Gallery's summary pair, then the newest exchange of [committed] (a user turn and
     * the model turn that answered it, with its tool calls) when there is one.
     */
    fun turnsAfterSummary(summary: String, committed: List<LiteRtTurn>): List<LiteRtTurn> {
        val pair = listOf(LiteRtTurn(fromUser = true, text = SUMMARY_INTRO), LiteRtTurn(fromUser = false, text = summary))
        val last = committed.takeLast(2)
        val newest = if (last.size == 2 && last[0].fromUser && !last[1].fromUser) last else emptyList()
        return pair + newest
    }

    companion object {
        /** The Gallery's `TOKEN_LIMIT_THRESHOLD_RATIO`. */
        const val TOKEN_LIMIT_THRESHOLD_RATIO = 0.75
        const val WORD_TO_TOKEN_RATIO = 0.75
        const val MIN_SUMMARY_WORD_LIMIT = 50
        const val MAX_SUMMARY_WORD_LIMIT = 1000
        const val FAILURE_BACKOFF_CHECKS = 3

        /** The Gallery's first message of the restarted conversation. */
        const val SUMMARY_INTRO = "Here is a summary of our past conversation for context:"
    }
}
