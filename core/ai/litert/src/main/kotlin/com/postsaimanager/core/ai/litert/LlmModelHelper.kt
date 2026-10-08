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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's runtime/LlmModelHelper.kt (v1.0.20). The Gallery's
// Model/Task/Context types, the image and audio inputs, the token counting and the context-compaction hooks are removed; the
// model's settings arrive as an LlmModelConfig and the engine and conversation travel in an LlmModelInstance handle.

package com.postsaimanager.core.ai.litert

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import com.postsaimanager.core.model.Accelerator

typealias ResultListener =
    (partialResult: String, done: Boolean, partialThinkingResult: String?) -> Unit

typealias CleanUpListener = () -> Unit

/**
 * What a LiteRT-LM model is loaded with. Replaces the Gallery's `Model` (its allowlist entry, its per-model settings): here the
 * values come from the catalogue descriptor and the user's inference settings.
 *
 * @param accelerator where the engine runs; GPU falls back to CPU when the GPU engine cannot start.
 * @param maxTokens the context window (`EngineConfig.maxNumTokens`).
 * @param supportImage start the vision encoder too (the Gallery's `supportImage`, `EngineConfig.visionBackend`), so the model can
 *   look at a picture. Off for a plain chat: the engine is restarted with it on only when a reply carries a picture.
 */
data class LlmModelConfig(
    val modelPath: String,
    val accelerator: Accelerator,
    val maxTokens: Int,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
    val supportImage: Boolean = false,
)

/**
 * Base interface for an LLM runtime: initialise, manage the conversation, stream one inference, stop it, clean up. The Gallery
 * defines it for every one of its runtimes; this app has the one LiteRT-LM implementation, [LlmChatModelHelper].
 */
internal interface LlmModelHelper {

    /**
     * Initialises the engine and its first conversation.
     *
     * @return the live instance, which says which accelerator actually started.
     * @throws Exception when neither the requested accelerator nor the CPU fallback could start.
     */
    fun initialize(
        config: LlmModelConfig,
        systemInstruction: Contents? = null,
        initialMessages: List<Message> = listOf(),
        tools: List<ToolProvider> = listOf(),
    ): LlmModelInstance

    /**
     * Replaces the instance's conversation: a new system instruction, a new history to continue from, the sampling of [config].
     * The engine (the weights) stays loaded.
     *
     * @param tools the tools the model may call; LiteRT-LM runs them itself and feeds their results back to the model (automatic
     *   tool calling, on by default), so the stream of [runInference] carries the model's text and no tool protocol.
     */
    fun resetConversation(
        instance: LlmModelInstance,
        config: LlmModelConfig,
        systemInstruction: Contents? = null,
        initialMessages: List<Message> = listOf(),
        tools: List<ToolProvider> = listOf(),
    )

    /**
     * How many tokens the instance's conversation holds now (history, system instruction and tool results included), or 0 when
     * unknown. The Gallery's context compaction reads the same number (`LlmConversationInstance.getTokenCount`).
     */
    fun tokenCount(instance: LlmModelInstance): Int

    /**
     * Asks the instance's live conversation [request] and waits for the whole answer (the Gallery's `LlmConversationInstance.sendMessage`
     * used by its context compactor). The request and the answer become part of that conversation. Null when the engine failed or
     * said nothing.
     */
    fun summarize(instance: LlmModelInstance, request: String): String?

    /**
     * One answer to [prompt] under [system] from a conversation of its own (no history, no tools), closed again afterwards. The
     * engine holds one native conversation at a time, so the instance's live conversation is closed first and is dead afterwards:
     * the caller rebuilds it before the next reply (it keeps the turns itself). Null when the engine failed or said nothing.
     */
    fun generateOnce(instance: LlmModelInstance, config: LlmModelConfig, system: String, prompt: String): String?

    /**
     * As [generateOnce], with pictures in front of [prompt] and the answer constrained to the JSON [schema] (LiteRT-LM
     * `ConversationConfig.enableResponseFormat` and `ResponseFormat.json`), thinking off. The instance must have been started with
     * [LlmModelConfig.supportImage] when [images] is not empty. A generation still running after [timeoutMs] is cancelled. Null
     * when the engine failed, timed out or said nothing; the default says the runtime cannot constrain its output.
     */
    fun generateStructured(
        instance: LlmModelInstance,
        config: LlmModelConfig,
        system: String,
        prompt: String,
        images: List<ByteArray>,
        schema: String,
        maxTokens: Int,
        timeoutMs: Long,
    ): String? = null

    /** The engine's own prefill and decode counters of the last [generateStructured], as a line for the timing log; empty when it had none. */
    val lastBenchmark: String get() = ""

    /** Closes the conversation and the engine and frees the model. */
    fun cleanUp(instance: LlmModelInstance, onDone: () -> Unit = {})

    /**
     * Streams one reply to [input].
     *
     * @param resultListener called with each partial result; `done = true` once, at the end (also after a cancellation).
     * @param onError called when the engine fails (a cancellation is not a failure).
     * @param extraContext e.g. `enable_thinking`.
     * @param images the pictures of this message, encoded (PNG or JPEG), put in front of the text as the Gallery does; the
     *   instance must have been started with [LlmModelConfig.supportImage].
     */
    fun runInference(
        instance: LlmModelInstance,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener = {},
        onError: (message: String) -> Unit = {},
        extraContext: Map<String, String> = emptyMap(),
        images: List<ByteArray> = emptyList(),
    )

    /** Stops the ongoing response generation. */
    fun stopResponse(instance: LlmModelInstance)
}
