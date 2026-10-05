package com.postsaimanager.applock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires the platform's signals into [AppLockState] — the only place that knows about
 * `ProcessLifecycleOwner` and the stored preferences. Started once from `PostsAiManagerApp`,
 * main process only.
 *
 * `ProcessLifecycleOwner` is the right source rather than an activity's `onStop`: it reports the
 * whole app leaving the foreground, and it delays that report by about 700 ms so an activity
 * being recreated for a configuration change (rotation, dark mode, locale) never looks like
 * "went to the background". Work that keeps running in WorkManager is not affected: only the UI
 * is gated.
 */
@Singleton
class AppLockCoordinator @Inject constructor(
    private val appLock: AppLockState,
    private val preferences: UserPreferencesRepository,
) {

    /** Must be called on the main thread. */
    fun start(scope: CoroutineScope) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> appLock.onBackgrounded()
                    Lifecycle.Event.ON_START -> appLock.onForegrounded()
                    else -> Unit
                }
            },
        )
        scope.launch {
            preferences.getUserPreferences()
                .map { it.biometricEnabled to it.appLockTimeoutMinutes }
                .distinctUntilChanged()
                .collect { (enabled, timeoutMinutes) -> appLock.applySettings(enabled, timeoutMinutes) }
        }
    }
}
