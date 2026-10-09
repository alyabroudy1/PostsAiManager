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
// Firebase and the metrics tracker, benchmarking, speculative decoding, audio input, the audio backend, NPU
// and TPU, the Model/Task/Context types and the model manager's initialisation states. Added: the GPU-to-CPU fallback
// when the GPU engine cannot start, and the instance handle (a Gallery Model carried it). Image input is the Gallery's again
// (EngineConfig.visionBackend, Content.ImageBytes in front of the text); audio is still not taken.

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
import com.google.ai.edge.litertlm.ResponseFormat
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
    /** True when the engine was started with its vision encoder, so a message may carry pictures. */
    val supportsImage: Boolean = false,
)

internal object LlmChatModelHelper : LlmModelHelper {

    @Volatile
    override var lastBenchmark: String = ""
        private set

    override fun initialize(
        config: LlmModelConfig,
        systemInstruction: Contents?,
        initialMessages: List<Message>,
        tools: List<ToolProvider>,
    ): LlmModelInstance {
        Log.i(TAG, "initialize: requested accelerator=${config.accelerator}, context=${config.maxTokens}")
        val (engine, accelerator) = startEngine(config)
        return try {
            LlmModelInstance(
                engine,
                newConversation(engine, config, systemInstruction, initialMessages, tools),
                accelerator,
                supportsImage = config.supportImage,
            )
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
        fun backendOf() =
            when (accelerator) {
                Accelerator.CPU -> Backend.CPU()
                Accelerator.GPU -> Backend.GPU()
            }
        val engineConfig =
            EngineConfig(
                modelPath = config.modelPath,
                backend = backendOf(),
                // As the Gallery: the vision encoder is part of the engine, started only for a model that is to look at pictures.
                visionBackend = if (config.supportImage) backendOf() else null,
                maxNumTokens = config.maxTokens,
            )
        val engine = Engine(engineConfig)
        try {
            // Diagnostics (branch diag/chat-timing): benchmark counters on, to log prefill/decode tokens and rates per reply.
            ExperimentalFlags.enableBenchmark = true
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
        // As in the Gallery's agent chat (`enableConversationConstrainedDecoding = true` in AgentChatTaskModule.kt): constrained
        // decoding is on when the conversation has tools, so a tool call comes out in the format the engine can parse; plain chat
        // stays off. (4ce2d88 had it off: it made no difference to the garbled digits, which were the GPU engine's.)
        ExperimentalFlags.enableConversationConstrainedDecoding = tools.isNotEmpty()
        Log.i(
            TAG,
            "conversation: topK=${config.topK} topP=${config.topP} temperature=${config.temperature} context=${config.maxTokens} " +
                "tools=${tools.size} system=${systemInstruction?.toString()?.length ?: 0} chars initialMessages=${initialMessages.size}",
        )
        com.postsaimanager.core.common.util.TimingLog.at("createConversation: constrainedDecoding=${tools.isNotEmpty()} backend=${config.accelerator}")
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
                    // LiteRT-LM 0.18 has no prefill-only call on a Conversation, but its config can prefill the "preface" (system
                    // instruction, tool schemas, initial messages) when the conversation is made, instead of lazily with the first
                    // message. So building the conversation ahead of time (LiteRtChatEngine.warmUp) leaves the first send with only
                    // its own turn to prefill. The cost moves, it does not change: the same tokens, earlier.
                    prefillPrefaceOnInit = systemInstruction != null || initialMessages.isNotEmpty(),
                )
            )
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            com.postsaimanager.core.common.util.TimingLog.at("createConversation returned")
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

    /** Diagnostics: the engine's own prefill/decode counters for the last send, as one log line. */
    @OptIn(ExperimentalApi::class)
    fun benchmarkLine(instance: LlmModelInstance): String =
        try {
            val b = instance.conversation.getBenchmarkInfo()
            "bench: ttft=${"%.2f".format(b.timeToFirstTokenInSecond)}s prefill=${b.lastPrefillTokenCount} tok @ ${"%.1f".format(b.lastPrefillTokensPerSecond)} tok/s, " +
                "decode=${b.lastDecodeTokenCount} tok @ ${"%.1f".format(b.lastDecodeTokensPerSecond)} tok/s, contextTokens=${instance.conversation.getTokenCount()}"
        } catch (e: Throwable) {
            "bench: unavailable (${e.message})"
        }

    override fun tokenCount(instance: LlmModelInstance): Int =
        try {
            instance.conversation.getTokenCount()
        } catch (e: Exception) {
            0
        }

    override fun summarize(instance: LlmModelInstance, request: String): String? =
        try {
            instance.conversation.sendMessage(Contents.of(request)).toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "summary failed: ${e.message}")
            null
        }

    override fun generateOnce(instance: LlmModelInstance, config: LlmModelConfig, system: String, prompt: String): String? =
        try {
            runCatching { instance.conversation.close() }
            val conversation = newConversation(instance.engine, config, Contents.of(system), emptyList(), emptyList())
            // Kept as the instance's conversation so that a clean-up closes whichever is open; closed again right after the answer.
            instance.conversation = conversation
            try {
                conversation.sendMessage(Contents.of(prompt)).toString().takeIf { it.isNotBlank() }
            } finally {
                runCatching { conversation.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "one-off generation failed: ${e.message}")
            null
        }

    /**
     * "Gemma reads the letter": a conversation of its own whose answer LiteRT-LM constrains to [schema] (LLGuidance behind
     * `ConversationConfig.enableResponseFormat` and `ResponseFormat.json`). Thinking is off, there are no tools, the pictures come
     * before the text as in a chat message. The engine's own counters (prefill and decode tokens and rates) go to the timing log
     * before the conversation is closed; a generation still running at [timeoutMs] is cancelled by a daemon timer.
     */
    @OptIn(ExperimentalApi::class) // opt-in experimental flags and the response format
    override fun generateStructured(
        instance: LlmModelInstance,
        config: LlmModelConfig,
        system: String,
        prompt: String,
        images: List<ByteArray>,
        schema: String,
        maxTokens: Int,
        timeoutMs: Long,
        leadPrompt: String?,
        onLead: ((String) -> Unit)?,
        keepOpen: Boolean,
    ): String? =
        try {
            lastBenchmark = ""
            runCatching { instance.conversation.close() }
            // The sampling of THIS conversation (every turn of it, the lead summary and the follow-ups included): the log shows what the engine got.
            Log.i(
                TAG,
                "structured conversation: topK=${config.topK} topP=${config.topP} temperature=${config.temperature} maxOutputToken=$maxTokens",
            )
            val conversation = instance.engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = config.topK,
                        topP = config.topP.toDouble(),
                        temperature = config.temperature.toDouble(),
                    ),
                    systemInstruction = Contents.of(system),
                    enableResponseFormat = true,
                    maxOutputToken = maxTokens,
                ),
            )
            // Kept as the instance's conversation so that a clean-up closes whichever is open, and so a stop reaches it.
            instance.conversation = conversation
            var keep = false
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val timer = java.util.Timer("pam-structured-timeout", true)
            timer.schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        timedOut.set(true)
                        runCatching { conversation.cancelProcess() }
                    }
                },
                timeoutMs,
            )
            try {
                val thinkingOff = mapOf<String, Any>("enable_thinking" to false)
                val contents = mutableListOf<Content>()
                for (image in images) contents.add(Content.ImageBytes(image))
                val answer = if (leadPrompt == null) {
                    contents.add(Content.Text(prompt))
                    // A blank schema is a free-text answer (the "Questions" reader style): no response format on the message.
                    if (schema.isBlank()) {
                        conversation.sendMessage(Contents.of(contents), extraContext = thinkingOff).toString()
                    } else {
                        conversation.sendMessage(
                            Contents.of(contents),
                            extraContext = thinkingOff,
                            responseFormat = ResponseFormat.json(schema),
                        ).toString()
                    }
                } else {
                    // Two turns in one conversation (the letter and the pictures are prefilled once): the first is answered in free text, with
                    // no response format (the conversation was created with enableResponseFormat, but a format applies per message), and is
                    // handed on at once; the second one is the constrained JSON, and its cache holds everything of the first.
                    contents.add(Content.Text(leadPrompt))
                    val lead = conversation.sendMessage(Contents.of(contents), extraContext = thinkingOff).toString().trim()
                    if (lead.isNotEmpty()) runCatching { onLead?.invoke(lead) }
                    if (schema.isBlank()) {
                        conversation.sendMessage(Contents.of(prompt), extraContext = thinkingOff).toString()
                    } else {
                        conversation.sendMessage(
                            Contents.of(prompt),
                            extraContext = thinkingOff,
                            responseFormat = ResponseFormat.json(schema),
                        ).toString()
                    }
                }
                // Read before the conversation is closed; the engine puts it on its one timing line.
                lastBenchmark = benchmarkLine(instance)
                answer.takeIf { it.isNotBlank() }?.also { keep = keepOpen && !timedOut.get() }
            } finally {
                timer.cancel()
                if (timedOut.get()) Log.w(TAG, "structured generation cancelled after ${timeoutMs}ms")
                // Kept open only after a good answer: the follow-up questions go on in it (the letter and the pictures are in its cache).
                if (!keep) runCatching { conversation.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "structured generation failed: ${e.message}")
            null
        }

    @OptIn(ExperimentalApi::class) // the response format
    override fun continueStructured(instance: LlmModelInstance, prompt: String, schema: String, timeoutMs: Long): String? {
        val conversation = instance.conversation
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = java.util.Timer("pam-followup-timeout", true)
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    timedOut.set(true)
                    runCatching { conversation.cancelProcess() }
                }
            },
            timeoutMs,
        )
        return try {
            conversation.sendMessage(
                Contents.of(prompt),
                extraContext = mapOf<String, Any>("enable_thinking" to false),
                responseFormat = ResponseFormat.json(schema),
            ).toString().takeIf { it.isNotBlank() && !timedOut.get() }
                .also { if (it == null) runCatching { conversation.close() } }
        } catch (e: Exception) {
            Log.w(TAG, "follow-up generation failed: ${e.message}")
            runCatching { conversation.close() }
            null
        } finally {
            timer.cancel()
        }
    }

    override fun closeConversation(instance: LlmModelInstance) {
        runCatching { instance.conversation.close() }
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
        images: List<ByteArray>,
    ) {
        val conversation = instance.conversation

        // Step 1: Assemble the prompt. The text goes in as it is: the model file carries its own chat template and LiteRT-LM
        // applies it, so nothing here wraps it in turn markers. As in the Gallery, the pictures come first and the text after
        // them, "to ensure proper autoregressive token sequencing".
        val contents = mutableListOf<Content>()
        for (image in images) {
            contents.add(Content.ImageBytes(image))
        }
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
