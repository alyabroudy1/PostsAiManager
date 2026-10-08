package com.postsaimanager.core.ai.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.model.ModelRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The app process's one binding to the `:inference` process, shared by every engine that talks to it ([RemoteAiEngine] for
 * llama.cpp, [RemoteLiteRtChatEngine] for LiteRT-LM).
 *
 * It owns the three things that must exist once, not once per engine: the binding itself (and its death watch), the mutex that
 * serialises every call touching the model, and the record of which runtime's model is resident. The last two are what make
 * "one engine resident at a time" and "background reading waits while a chat answer streams" true across both engines.
 */
@Singleton
class InferenceConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    var service: IInferenceService? = null
        private set

    /**
     * Serialises every call that touches the model on the app-process side of the AIDL boundary, across both engines — see
     * [com.postsaimanager.core.domain.ai.AiEngine]'s class KDoc, "One native context, two callers". `InferenceService`'s single
     * thread already serialises the native calls, but its cancellation flag is shared by callers, so only one logical caller is
     * ever in flight against the service. Held for the whole token stream, so a read queued behind a streaming chat answer
     * waits for it.
     *
     * Deliberately not taken by model loads: crash recovery loads while already holding it, and a non-reentrant [Mutex] would
     * deadlock on itself.
     */
    val engineMutex = Mutex()

    /** Whose model the `:inference` process holds, as far as this side knows: set by the engine that last loaded one. */
    @Volatile
    var resident: ModelRuntime? = null

    private val deathListeners = CopyOnWriteArrayList<(ModelRuntime?) -> Unit>()

    private val _crashes = MutableSharedFlow<InferenceCrash>(extraBufferCapacity = 4)

    /**
     * Crashes of the `:inference` process while it ran a model, whichever engine's: the one stream `InferenceCrashObserver`
     * listens to, so that a GPU crash blocks the GPU for the model that was running, llama.cpp's or LiteRT-LM's.
     */
    val crashes: SharedFlow<InferenceCrash> = _crashes.asSharedFlow()

    /** The engine that was running when the process died reports what it was running. */
    fun reportCrash(crash: InferenceCrash) {
        _crashes.tryEmit(crash)
    }

    /** [listener] is told when the `:inference` process dies, with the runtime whose model it was holding (null if none). */
    fun onDeath(listener: (ModelRuntime?) -> Unit) {
        deathListeners += listener
    }

    private val deathRecipient = IBinder.DeathRecipient {
        // The whole point of the boundary: observe the crash instead of dying with it.
        Log.e(TAG, "inference process died")
        service = null
        val heldBy = resident
        resident = null
        deathListeners.forEach { it(heldBy) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IInferenceService.Stub.asInterface(binder)
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /**
     * Binds `:inference`, or returns the already-bound service.
     *
     * Bounded by [CONNECT_TIMEOUT_MS]: without it, a `:inference` that dies during its own `Application.onCreate` (or never
     * starts at all — low memory, a `SecurityException` some OEMs throw for background service starts) leaves
     * [ServiceConnection.onServiceConnected] never called, and this call — and every caller awaiting it — would otherwise
     * suspend forever. `onBindingDied`/`onNullBinding` are the two documented callbacks for exactly that failure mode and
     * resolve immediately when the platform reports them; the timeout is the backstop for whatever neither one catches.
     */
    suspend fun connect(): IInferenceService? {
        service?.let { return it }
        val remote = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<IInferenceService?> { continuation ->
                val once = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        connection.onServiceConnected(name, binder)
                        if (continuation.isActive) continuation.resume(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        connection.onServiceDisconnected(name)
                    }

                    // Called when the platform gives up on ever restoring this binding — the process hosting the service died
                    // before (or instead of) connecting, and will not be revived automatically the way onServiceDisconnected's
                    // crash recovery is. Unbind so a later connect() starts a clean bind rather than layering a second
                    // registration onto a dead one.
                    override fun onBindingDied(name: ComponentName?) {
                        Log.e(TAG, "connect: binding to :inference died before it connected")
                        runCatching { context.unbindService(this) }
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        Log.e(TAG, "connect: :inference returned a null binder")
                        runCatching { context.unbindService(this) }
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                val intent = Intent(context, InferenceService::class.java)
                val bound = context.bindService(intent, once, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Covers both a normal coroutine cancellation and the withTimeoutOrNull above firing: either way, a bind that
                // never resolved must not stay registered waiting for a connection nothing is listening for any more.
                continuation.invokeOnCancellation { runCatching { context.unbindService(once) } }
            }
        }
        if (remote == null && service == null) {
            Log.e(TAG, "connect: timed out after ${CONNECT_TIMEOUT_MS}ms waiting for :inference")
        }
        return remote
    }

    private companion object {
        const val TAG = "InferenceConnection"

        /** A cold `:inference` start (process fork, classloading, this Application's `onCreate`) is part of what is waited on. */
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
