package com.postsaimanager

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.postsaimanager.core.ai.local.InferenceCrashObserver
import com.postsaimanager.core.ai.local.InferenceMemoryPressureObserver
import com.postsaimanager.core.data.worker.DocumentProcessingRecovery
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager builds workers through Hilt —
 * `ModelDownloadWorker` needs `ModelDownloader` injected, which the default factory cannot
 * supply. The manifest correspondingly removes WorkManager's automatic initializer, so
 * this on-demand configuration is the only one used.
 *
 * ### This class also starts in `:inference`
 *
 * `android:process=":inference"` on `InferenceService` (`core/ai/local`) does not give that
 * process its own `Application` subclass — Hilt field-injects *this same* [PostsAiManagerApp]
 * there too, on every cold start of the isolated process. Never eagerly inject anything whose
 * constructor (or whose dependency graph's constructors) does main-process-only
 * initialisation. ML Kit is the concrete trap: `TextRecognition.getClient(...)` throws
 * `IllegalStateException: MlKitContext has not been initialized` unless ML Kit's
 * `ContentProvider`-based init has run, and that provider is only merged into the main
 * process's manifest entry — never `:inference`'s. A plain `@Inject lateinit var` forces
 * Dagger to build the whole dependency chain during field injection; `documentProcessingRecovery`
 * below was exactly that (`DocumentProcessingRecovery` -> `DocumentProcessor` ->
 * `DocumentProcessingPipeline` -> `OcrService`, whose constructor calls `TextRecognition
 * .getClient`), which crashed `:inference` on every launch until this KDoc existed. Prefer
 * `dagger.Lazy<T>` for anything not cheap/side-effect-free to construct, and gate the `.get()`
 * (or the whole call) behind [isMainProcess].
 */
@HiltAndroidApp
class PostsAiManagerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Both observers describe themselves (see their own KDoc) as the *app process's* half of
    // their story — InferenceMemoryPressureObserver explicitly, InferenceCrashObserver by only
    // ever having something to react to when *this* process's RemoteAiEngine is the one issuing
    // generate() calls. Neither does anything unsafe in :inference (no ML Kit, no document
    // pipeline), so leaving them un-lazied is not the crash bug — but starting them there is
    // pointless double-work (a second ComponentCallbacks2 registration, a second crashEvents
    // collector on a RemoteAiEngine instance nothing ever drives), so they are still gated
    // below, defence in depth alongside documentProcessingRecovery.
    @Inject
    lateinit var inferenceMemoryPressureObserver: InferenceMemoryPressureObserver

    @Inject
    lateinit var inferenceCrashObserver: InferenceCrashObserver

    // Lazy: see the class KDoc. Field injection alone must not build DocumentProcessor's graph
    // in :inference — only resumeInterrupted() (called only in the main process, below) may.
    @Inject
    lateinit var documentProcessingRecovery: Lazy<DocumentProcessingRecovery>

    /** Process-lifetime scope for start-up work that must outlive `onCreate` returning. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        enableStrictModeInDebug()
        if (!isMainProcess()) return
        inferenceMemoryPressureObserver.start()
        inferenceCrashObserver.start()
        // Off Main, and after onCreate returns rather than blocking it: a document stuck at
        // PROCESSING because the app was killed mid-run (documentation/07-document-pipeline.md
        // §8) needs re-enqueuing, but that is a DAO read plus a WorkManager call, not
        // something the app's cold start should wait on.
        applicationScope.launch { documentProcessingRecovery.get().resumeInterrupted() }
    }

    /**
     * True when this [Application] instance is running in the main (default) process, as
     * opposed to `:inference` — see the class KDoc. Hilt field-injects the Application in
     * every process a manifest component declares, so `onCreate` runs here too.
     *
     * [Application.getProcessName] (API 28+) is a direct, no-IPC answer. Below that, this
     * repo's minSdk (26/27) has no equivalent on `Application`/`Context`, so it falls back to
     * [ActivityManager.getRunningAppProcesses] and matches this process's pid — one binder
     * call, made once, at cold start. The actual decision is [isMainProcess] (process name,
     * package name) -> Boolean below, kept pure and separate so it is testable without an
     * Android `Context`.
     */
    private fun isMainProcess(): Boolean =
        isMainProcess(currentProcessName(), packageName)

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val am = getSystemService(ActivityManager::class.java)
            val pid = Process.myPid()
            am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
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

/**
 * True when [processName] (this process's name, however it was obtained — see
 * [PostsAiManagerApp.currentProcessName]) names the main process rather than a satellite one
 * such as `:inference`. A null [processName] (an [ActivityManager] lookup that failed to find
 * this pid, e.g. under a test harness) is treated as "assume main" — the safer default, since
 * anything gated on this is a no-op if skipped, never a crash if run.
 *
 * Pure so it is unit-testable without an Android `Context`.
 */
internal fun isMainProcess(processName: String?, packageName: String): Boolean =
    processName == null || processName == packageName
