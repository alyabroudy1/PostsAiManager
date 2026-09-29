package com.postsaimanager.core.domain.applock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A clock that only moves forward and keeps counting while the device sleeps — the wall clock
 * would let a changed system time defeat the lock's grace period. Backed by
 * `SystemClock.elapsedRealtime()` in `:app`; a plain counter in tests.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/**
 * The single owner of "is the app locked right now?" — everything else (the lock screen, the
 * window's secure flag, the process-lifecycle observer, the Settings toggle) feeds this or reads
 * it, and nothing keeps a second copy.
 *
 * Pure Kotlin on purpose: the rules below are the security behaviour, so they are tested with a
 * fake [MonotonicClock] rather than on a device.
 *
 * ### The rules
 *
 * - **Cold start.** Until the stored settings arrive the app is *locked* and its window *secure*
 *   ([Snapshot.settingsKnown] is false). When they arrive, the app stays locked if the lock is
 *   on and unlocks straight away if it is off, so nothing flashes either way.
 * - **Going to the background** ([onBackgrounded]) stamps the time. **Coming back**
 *   ([onForegrounded]) locks if the lock is on and at least the configured timeout has passed.
 *   A timeout of 0 therefore locks on every return.
 * - **Rotation and other configuration changes are not backgrounding.** The caller feeds this
 *   from `ProcessLifecycleOwner`, which does not report a stop while an activity is only being
 *   recreated. If they ever do arrive as a foreground without a preceding background, or a
 *   background that is undone at once, the timeout math means no lock either way: a foreground
 *   with no stamp is ignored, and a stamp only counts once.
 * - **Toggling the lock in Settings** never locks the user out of the screen they are on:
 *   turning it on required a fresh authentication a moment ago, turning it off needs none.
 * - **App-initiated trips out of the app** (scanner, permission dialog, share sheet, settings)
 *   are announced through [ExternalFlowGuard] and do not lock on return, even at timeout 0,
 *   for at most [EXTERNAL_FLOW_GRACE_MINUTES]. The return consumes the tokens; an unrelated
 *   background after that locks as usual.
 * - Background work is unaffected: nothing here touches WorkManager.
 */
@Singleton
class AppLockState @Inject constructor(
    private val clock: MonotonicClock,
) : ExternalFlowGuard {

    /** What the UI needs to know. */
    data class Snapshot(
        /** False until [applySettings] has run once since process start. */
        val settingsKnown: Boolean = false,
        val enabled: Boolean = false,
        /** True from process start until settings say otherwise or the user authenticates. */
        val locked: Boolean = true,
    ) {
        /**
         * Whether the window must be `FLAG_SECURE` (blank in recents, no screenshots). True while
         * the settings are still loading too, so the very first frame is never exposed.
         */
        val secureWindow: Boolean get() = !settingsKnown || enabled
    }

    private val state = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    // Guarded by `this`, like every write to [state].
    private var timeoutMillis = 0L
    private var backgroundedAt: Long? = null

    /** Token id -> the elapsed time after which it no longer excuses a return. */
    private val externalFlows = mutableMapOf<Long, Long>()
    private var nextFlowId = 0L

    /** Feeds in the stored preferences. Called on every change, and once at cold start. */
    @Synchronized
    fun applySettings(enabled: Boolean, timeoutMinutes: Int) {
        val previous = state.value
        val locked = when {
            !enabled -> false
            !previous.settingsKnown -> true
            previous.enabled != enabled -> false
            else -> previous.locked
        }
        timeoutMillis = timeoutMinutes.coerceAtLeast(0) * MILLIS_PER_MINUTE
        state.value = Snapshot(settingsKnown = true, enabled = enabled, locked = locked)
    }

    /** The whole app has left the foreground (not merely an activity being recreated). */
    @Synchronized
    fun onBackgrounded() {
        if (backgroundedAt == null) backgroundedAt = clock.elapsedMillis()
    }

    /** The app is visible again. */
    @Synchronized
    fun onForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val now = clock.elapsedMillis()
        // Returning consumes every outstanding flow. It only excuses the trip if one was still
        // inside its grace window; past that the normal timeout rule decides.
        val excused = externalFlows.values.any { expiresAt -> now <= expiresAt }
        externalFlows.clear()
        val current = state.value
        if (!excused && current.enabled && now - since >= timeoutMillis) {
            state.value = current.copy(locked = true)
        }
    }

    /**
     * See [ExternalFlowGuard]. The token's protection lasts [EXTERNAL_FLOW_GRACE_MINUTES] from now.
     */
    @Synchronized
    override fun expect(reason: String): ExternalFlowToken {
        val now = clock.elapsedMillis()
        externalFlows.values.removeAll { expiresAt -> expiresAt < now }
        val token = ExternalFlowToken(nextFlowId++, reason)
        externalFlows[token.id] = now + EXTERNAL_FLOW_GRACE_MINUTES * MILLIS_PER_MINUTE
        return token
    }

    @Synchronized
    override fun finish(token: ExternalFlowToken?) {
        if (token != null) externalFlows.remove(token.id)
    }

    /** The user has just authenticated successfully. */
    @Synchronized
    fun unlock() {
        val current = state.value
        if (current.settingsKnown) state.value = current.copy(locked = false)
    }

    companion object {
        /** How long an [expect]ed external flow may keep the app unlocked on return. */
        const val EXTERNAL_FLOW_GRACE_MINUTES = 10L

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
