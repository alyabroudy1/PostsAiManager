package com.postsaimanager.core.ai.local

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frees the resident model when the **app** process comes under memory pressure.
 *
 * This is the app-process half of the memory-pressure story — [InferenceService] handles the
 * `:inference` process's own [android.app.Service.onTrimMemory] independently, since the two
 * processes are trimmed separately by the system. Both end up calling the same thing: unload
 * the model unless a load is genuinely mid-flight, in which case
 * [ModelLoadCoordinator.unloadOnMemoryPressure] backs off rather than blocking.
 *
 * Registered from [com.postsaimanager.PostsAiManagerApp.onCreate] — there is no existing
 * `androidx.startup` `Initializer` in this codebase to hook into (the only one declared,
 * WorkManager's, is disabled in the manifest in favour of Hilt's `Configuration.Provider`),
 * so this follows that same "inject and call from `onCreate`" shape rather than introducing
 * a new pattern for one class.
 */
@Singleton
class InferenceMemoryPressureObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RemoteAiEngine,
    @Dispatcher(PamDispatcher.DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : ComponentCallbacks2 {

    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    /** Call once, from [android.app.Application.onCreate]. */
    fun start() {
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            Log.w(TAG, "onTrimMemory($level) — unloading the AI model if idle")
            scope.launch { engine.onTrimMemory() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in ComponentCallbacks2; onTrimMemory covers this on API 14+.")
    override fun onLowMemory() = Unit

    private companion object {
        const val TAG = "InferenceMemoryPressure"
    }
}
