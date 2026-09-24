package com.postsaimanager.core.ai.local

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.postsaimanager.core.model.Accelerator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts llama.cpp in the `:inference` process.
 *
 * Everything native happens on the far side of a process boundary, so a C++ abort — which
 * spike Q3 measured killing the whole app — takes only this process with it. The user
 * loses the answer in flight; their documents, scans and unsaved edits survive.
 *
 * Deliberately **not** Hilt-injected. A `@AndroidEntryPoint` service would drag the Hilt
 * graph, and with it Room and DataStore, into a second process — duplicating the database
 * connection and defeating the isolation. This process owns exactly one thing: the model.
 */
class InferenceService : Service() {

    /**
     * Single-threaded: llama.cpp is not thread-safe per context, and serialising here
     * means the binder threads never touch native state concurrently.
     */
    private val executor = Executors.newSingleThreadExecutor()

    private var handle: Long = 0L
    private val cancelled = AtomicBoolean(false)

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelPath: String?, config: InferenceConfigParcel?): Boolean {
            if (modelPath == null || config == null) return false
            return submit {
                if (!LlamaNative.ensureLoaded()) return@submit false
                if (!File(modelPath).exists()) return@submit false

                // Free before load: the old model's weights are released before the new
                // ones are allocated, never after — never two resident models at once.
                // loadModel() in llama_jni.cpp guards this too (defense in depth), but the
                // Kotlin-side free is what makes it happen even before that JNI call starts.
                freeHandle()
                handle = LlamaNative.loadModel(
                    modelPath = modelPath,
                    contextTokens = config.contextTokens,
                    batchTokens = config.batchTokens,
                    threads = config.threads,
                    threadsBatch = config.threadsBatch,
                    useMmap = config.useMmap,
                    useMlock = config.useMlock,
                    flashAttention = config.flashAttention,
                    gpuLayers = config.gpuLayers,
                    accelerator = Accelerator.fromLabel(config.accelerator).ordinal,
                )
                handle != 0L
            } ?: false
        }

        override fun isReady(): Boolean = handle != 0L

        override fun recreateContext(config: InferenceConfigParcel?): Boolean {
            if (handle == 0L || config == null) return false
            return submit {
                LlamaNative.recreateContext(
                    handle = handle,
                    contextTokens = config.contextTokens,
                    batchTokens = config.batchTokens,
                    threads = config.threads,
                    threadsBatch = config.threadsBatch,
                    flashAttention = config.flashAttention,
                )
            } ?: false
        }

        override fun hasNativeChatTemplate(): Boolean =
            handle != 0L && (submit { LlamaNative.hasChatTemplate(handle) } ?: false)

        override fun formatChat(
            roles: Array<out String>?,
            contents: Array<out String>?,
            addAssistant: Boolean,
        ): String? {
            if (handle == 0L || roles == null || contents == null) return null
            return submit {
                LlamaNative.formatChat(
                    handle = handle,
                    roles = Array(roles.size) { roles[it] },
                    contents = Array(contents.size) { contents[it] },
                    addAssistant = addAssistant,
                )
            }
        }

        override fun startGeneration(
            prompt: String?,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            seed: Long,
            grammar: String?,
            callback: ITokenCallback?,
        ): Boolean {
            if (handle == 0L || prompt == null || callback == null) return false

            cancelled.set(false)

            // Queued rather than run inline: the binder thread returns immediately and
            // tokens arrive via the oneway callback, so the caller is never blocked.
            executor.execute {
                try {
                    val started = LlamaNative.startGeneration(
                        handle, prompt, maxTokens, temperature, topK, topP, seed, grammar,
                    )
                    if (!started) {
                        callback.onError("The prompt could not be tokenised.")
                        return@execute
                    }

                    while (!cancelled.get()) {
                        val token = LlamaNative.nextToken(handle) ?: break
                        callback.onToken(token)
                    }
                    LlamaNative.stopGeneration(handle)
                    callback.onComplete()
                } catch (e: RemoteException) {
                    // The client vanished mid-stream. Stop generating for a listener that
                    // no longer exists.
                    Log.w(TAG, "client disconnected during generation", e)
                    runCatching { LlamaNative.stopGeneration(handle) }
                } catch (e: Throwable) {
                    // A native abort cannot be caught here — that is precisely why this
                    // runs in its own process. This handles the survivable failures.
                    Log.e(TAG, "generation failed", e)
                    runCatching { callback.onError(e.message ?: "Generation failed.") }
                }
            }
            return true
        }

        override fun cancelGeneration() {
            cancelled.set(true)
        }

        override fun openChatSession(systemPrompt: String?): Boolean {
            if (handle == 0L) return false
            return submit { LlamaNative.openChatSession(handle, systemPrompt.orEmpty()) } ?: false
        }

        override fun primeChatSession(roles: Array<out String>?, contents: Array<out String>?): Boolean {
            if (handle == 0L || roles == null || contents == null) return false
            return submit {
                LlamaNative.primeChatSession(
                    handle,
                    Array(roles.size) { roles[it] },
                    Array(contents.size) { contents[it] },
                )
            } ?: false
        }

        override fun sendChatMessage(
            userText: String?,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            seed: Long,
            grammar: String?,
            noThink: Boolean,
            callback: ITokenCallback?,
        ): Boolean {
            if (handle == 0L || userText == null || callback == null) return false

            cancelled.set(false)

            // Same pull-based streaming shape as startGeneration — see its doc.
            executor.execute {
                try {
                    val started = LlamaNative.sendChatMessage(
                        handle, userText, maxTokens, temperature, topK, topP, seed, grammar, noThink,
                    )
                    if (!started) {
                        callback.onError("The chat turn could not be started.")
                        return@execute
                    }

                    while (!cancelled.get()) {
                        val token = LlamaNative.nextToken(handle) ?: break
                        callback.onToken(token)
                    }
                    LlamaNative.stopGeneration(handle)
                    callback.onComplete()
                } catch (e: RemoteException) {
                    Log.w(TAG, "client disconnected during generation", e)
                    runCatching { LlamaNative.stopGeneration(handle) }
                } catch (e: Throwable) {
                    Log.e(TAG, "chat generation failed", e)
                    runCatching { callback.onError(e.message ?: "Generation failed.") }
                }
            }
            return true
        }

        override fun commitChatReply(answer: String?) {
            if (handle == 0L || answer == null) return
            submit { LlamaNative.commitChatReply(handle, answer) }
        }

        override fun resetChatSession() {
            if (handle == 0L) return
            submit { LlamaNative.resetChatSession(handle) }
        }

        override fun unloadModel() {
            submit { freeHandle() }
        }

        override fun availableAccelerators(): IntArray {
            if (!LlamaNative.ensureLoaded()) return intArrayOf(0) // CPU only
            val accelerators = submit { LlamaNative.availableAccelerators() } ?: intArrayOf(0)
            val description = submit { LlamaNative.backendDescription() } ?: "<unavailable>"
            Log.i(
                TAG,
                "availableAccelerators() -> ${accelerators.toList()} ; backendDescription() -> $description",
            )
            return accelerators
        }

        override fun lastLoadDevices(): String =
            submit { LlamaNative.lastLoadDevices() } ?: "none"
    }

    /** Runs [block] on the inference thread and waits — binder calls are already off-main. */
    private fun <T> submit(block: () -> T): T? = try {
        executor.submit(block).get()
    } catch (e: Exception) {
        Log.e(TAG, "inference task failed", e)
        null
    }

    private fun freeHandle() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Frees the model under real memory pressure.
     *
     * No separate "not loaded" flag is needed for [IInferenceService.isReady] to reflect
     * this — it already reads [handle] directly, so [RemoteAiEngine] sees `isReady() ==
     * false` on its very next call and reloads lazily through [ModelLoadCoordinator].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            Log.w(TAG, "onTrimMemory($level) — freeing the resident model")
            submit { freeHandle() }
        }
    }

    override fun onDestroy() {
        submit { freeHandle() }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "InferenceService"
    }
}
