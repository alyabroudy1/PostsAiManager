package com.postsaimanager

import android.app.Application
import android.os.StrictMode
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
        enableStrictModeInDebug()
        inferenceMemoryPressureObserver.start()
        inferenceCrashObserver.start()
    }

    /**
     * Debug-only main-thread policy — logs (does not crash on) disk/network access and
     * other blocking calls made from Main. StrictMode has no detector specific to "blocking
     * AIDL/binder call" (that was defect 1's actual root cause — see `RemoteAiEngine`'s
     * `withContext(ioDispatcher)` wrapping every call that crosses into the `:inference`
     * process), so this alone would not have caught it; it is here as a general regression
     * net for the broader class of "something slow snuck onto Main", surfaced in
     * `adb logcat -s StrictMode` rather than only as "the UI froze for a few seconds".
     * `penaltyLog` only, never `penaltyDeath`: a false positive must not crash a debug build
     * a tester is using.
     */
    private fun enableStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }
}
