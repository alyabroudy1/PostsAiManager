package com.postsaimanager.core.ai.local

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
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

        override fun loadModel(modelPath: String?, contextTokens: Int, threads: Int): Boolean {
            if (modelPath == null) return false
            return submit {
                if (!LlamaNative.ensureLoaded()) return@submit false
                if (!File(modelPath).exists()) return@submit false

                freeHandle()
                handle = LlamaNative.loadModel(modelPath, contextTokens, threads)
                handle != 0L
            } ?: false
        }

        override fun isReady(): Boolean = handle != 0L

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
                        handle, prompt, maxTokens, temperature, grammar,
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

        override fun unloadModel() {
            submit { freeHandle() }
        }
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

    override fun onDestroy() {
        submit { freeHandle() }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "InferenceService"
    }
}
