/*
 * Copyright 2025 Google LLC
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

// Modified by PostsAiManager: adapted from the Google AI Edge Gallery's ui/llmchat/LlmChatModelHelper.kt (v1.0.20). Removed:
// Firebase and the metrics tracker, benchmarking, speculative decoding, image and audio inputs, the vision/audio backends, NPU
// and TPU, the Model/Task/Context types and the model manager's initialisation states. Added: the GPU-to-CPU fallback
// when the GPU engine cannot start, and the instance handle (a Gallery Model carried it).

package com.postsaimanager.core.ai.litert

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.postsaimanager.core.model.Accelerator
import java.util.concurrent.CancellationException

private const val TAG = "PamLiteRt"

/** The key of the reasoning trace in a streamed message's channels (the Gallery's `THOUGHT_CHANNEL`). */
internal const val THOUGHT_CHANNEL = "thought"

/**
 * Holds the runtime state for an initialised LiteRT-LM model.
 *
 * @param engine The underlying LiteRT-LM [Engine] managing model weights and hardware acceleration.
 * @param conversation The active [Conversation] session handling prompt history and token generation.
 * @param accelerator The accelerator the engine actually started on (the requested GPU may have fallen back to CPU).
 */
internal data class LlmModelInstance(
    val engine: Engine,
    var conversation: Conversation,
    val accelerator: Accelerator,
)

internal object LlmChatModelHelper : LlmModelHelper {

    override fun initialize(
        config: LlmModelConfig,
        systemInstruction: Contents?,
        initialMessages: List<Message>,
        tools: List<ToolProvider>,
    ): LlmModelInstance {
        Log.i(TAG, "initialize: requested accelerator=${config.accelerator}, context=${config.maxTokens}")
        val (engine, accelerator) = startEngine(config)
        return try {
            LlmModelInstance(engine, newConversation(engine, config, systemInstruction, initialMessages, tools), accelerator)
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw e
        }
    }

    /** Starts the engine on the requested accelerator, and on the CPU when the GPU engine cannot start. */
    private fun startEngine(config: LlmModelConfig): Pair<Engine, Accelerator> {
        return try {
            startEngineOn(config, config.accelerator) to config.accelerator
        } catch (e: Exception) {
            if (config.accelerator == Accelerator.CPU) throw e
            Log.w(TAG, "the ${config.accelerator} engine could not start (${e.message}); falling back to CPU")
            startEngineOn(config, Accelerator.CPU) to Accelerator.CPU
        }.also { (_, accelerator) -> Log.i(TAG, "engine ready: backend=$accelerator") }
    }

    @OptIn(ExperimentalApi::class) // opt-in experimental flags
    private fun startEngineOn(config: LlmModelConfig, accelerator: Accelerator): Engine {
        val backend =
            when (accelerator) {
                Accelerator.CPU -> Backend.CPU()
                Accelerator.GPU -> Backend.GPU()
            }
        val engineConfig =
            EngineConfig(
                modelPath = config.modelPath,
                backend = backend,
                maxNumTokens = config.maxTokens,
            )
        val engine = Engine(engineConfig)
        try {
            ExperimentalFlags.enableBenchmark = false
            engine.initialize()
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw e
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
        return engine
    }

    @OptIn(ExperimentalApi::class) // opt-in experimental flags
    private fun newConversation(
        engine: Engine,
        config: LlmModelConfig,
        systemInstruction: Contents?,
        initialMessages: List<Message>,
        tools: List<ToolProvider>,
    ): Conversation {
        // As the Gallery's agent chat does (enableConversationConstrainedDecoding = true): with tools, decoding is constrained so a
        // tool call the model starts is well-formed. Plain chat, with no tools, is not constrained. (Tried off on the phone: Gemma 4
        // E2B then stopped calling the tools at all and answered "I do not have the tool".)
        ExperimentalFlags.enableConversationConstrainedDecoding = tools.isNotEmpty()
        try {
            return engine.createConversation(
                ConversationConfig(
                    samplerConfig =
                        SamplerConfig(
                            topK = config.topK,
                            topP = config.topP.toDouble(),
                            temperature = config.temperature.toDouble(),
                        ),
                    systemInstruction = systemInstruction,
                    initialMessages = initialMessages,
                    // Automatic tool calling stays at its default (true), as in the Gallery: LiteRT-LM calls the tool, appends its
                    // result to the conversation and lets the model continue, all inside one sendMessageAsync.
                    tools = tools,
                )
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    override fun resetConversation(
        instance: LlmModelInstance,
        config: LlmModelConfig,
        systemInstruction: Contents?,
        initialMessages: List<Message>,
        tools: List<ToolProvider>,
    ) {
        Log.d(TAG, "Resetting conversation")
        try {
            instance.conversation.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close previous conversation: ${e.message}", e)
        }
        instance.conversation = newConversation(instance.engine, config, systemInstruction, initialMessages, tools)
    }

    override fun cleanUp(instance: LlmModelInstance, onDone: () -> Unit) {
        try {
            instance.conversation.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close the conversation: ${e.message}")
        }

        try {
            instance.engine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close the engine: ${e.message}")
        }

        onDone()
        Log.d(TAG, "Clean up done.")
    }

    override fun stopResponse(instance: LlmModelInstance) {
        try {
            instance.conversation.cancelProcess()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Conversation is not alive, cannot cancel process", e)
        }
    }

    override fun runInference(
        instance: LlmModelInstance,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit,
        extraContext: Map<String, String>,
    ) {
        val conversation = instance.conversation

        // Step 1: Assemble the prompt. The text goes in as it is: the model file carries its own chat template and LiteRT-LM
        // applies it, so nothing here wraps it in turn markers.
        val contents = mutableListOf<Content>()
        if (input.trim().isNotEmpty()) {
            contents.add(Content.Text(input))
        }

        // Step 2: Configure extra runtime parameters (such as thinking reasoning mode).
        val enableThinking = extraContext["enable_thinking"] == "true"
        val finalExtraContext: Map<String, Any> = extraContext + ("enable_thinking" to enableThinking)

        // Step 3: Dispatch asynchronous streaming inference to the native LiteRT-LM engine.
        conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val text = message.toString()
                    val thinking = message.channels[THOUGHT_CHANNEL]
                    resultListener(text, false, thinking)
                }

                override fun onDone() {
                    resultListener("", true, null)
                }

                override fun onError(throwable: Throwable) {
                    if (throwable is CancellationException) {
                        // The inference was cancelled.
                        Log.i(TAG, "The inference is cancelled.")
                        resultListener("", true, null)
                    } else {
                        // Engine error or crash.
                        Log.e(TAG, "onError", throwable)
                        onError("Error: ${throwable.message}")
                    }
                }
            },
            finalExtraContext,
        )
    }
}
