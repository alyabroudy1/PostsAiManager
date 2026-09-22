package com.postsaimanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.postsaimanager.core.ai.local.InferenceCrashObserver
import com.postsaimanager.core.ai.local.InferenceMemoryPressureObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager builds workers through Hilt —
 * `ModelDownloadWorker` needs `ModelDownloader` injected, which the default factory cannot
 * supply. The manifest correspondingly removes WorkManager's automatic initializer, so
 * this on-demand configuration is the only one used.
 */
@HiltAndroidApp
class PostsAiManagerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var inferenceMemoryPressureObserver: InferenceMemoryPressureObserver

    @Inject
    lateinit var inferenceCrashObserver: InferenceCrashObserver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        inferenceMemoryPressureObserver.start()
        inferenceCrashObserver.start()
    }
}
