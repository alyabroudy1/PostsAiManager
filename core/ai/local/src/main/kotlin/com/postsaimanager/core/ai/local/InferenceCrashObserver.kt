package com.postsaimanager.core.ai.local

import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a GPU crash into a standing "don't try GPU again for this model" fact.
 *
 * [AiEngine.crashEvents] reports *that* generation crashed and *what* config it was
 * running with; this is the one listener that decides what to do about it — block GPU for
 * that model via [InferenceSettingsRepository], so the next load (whether the user retries
 * immediately or opens the app tomorrow) falls back to CPU instead of repeating the same
 * abort. [CatalogActiveModelProvider][com.postsaimanager.core.ai.catalog
 * .CatalogActiveModelProvider] is what actually reads the block back out, both to size the
 * effective [com.postsaimanager.core.model.InferenceConfig] and to grey the option out in
 * the chat header sheet's schema.
 *
 * A CPU crash reported here is left alone — CPU has no fallback to switch to, and treating
 * every crash as "block whatever ran" would eventually block the only accelerator every
 * model ships supporting.
 *
 * Registered the same way as [InferenceMemoryPressureObserver] — injected and started once
 * from [com.postsaimanager.PostsAiManagerApp.onCreate] — for the same reason: there is no
 * `androidx.startup` hook in this codebase worth introducing for one class.
 */
@Singleton
class InferenceCrashObserver @Inject constructor(
    private val engine: AiEngine,
    private val inferenceSettingsRepository: InferenceSettingsRepository,
    @Dispatcher(PamDispatcher.DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    /** Call once, from [android.app.Application.onCreate]. */
    fun start() {
        scope.launch {
            engine.crashEvents.collect { crash ->
                val modelId = crash.modelId
                if (modelId != null && crash.config.accelerator == Accelerator.GPU) {
                    // Wrapped: `Log` is not mocked under a plain JVM unit test (this class
                    // has one — InferenceCrashObserverTest — unlike most of this package,
                    // which only device tests exercise), and a logging call is not worth
                    // failing the actual effect over.
                    runCatching {
                        Log.w(
                            TAG,
                            "GPU crash while running $modelId — blocking GPU for it on this device",
                        )
                    }
                    inferenceSettingsRepository.blockGpu(modelId)
                }
            }
        }
    }

    private companion object {
        const val TAG = "InferenceCrashObserver"
    }
}
