package com.postsaimanager.core.domain.applock

/** Whether this device can authenticate the user at all, right now. */
enum class DeviceAuthAvailability {
    /** A strong biometric or the screen-lock credential (PIN, pattern, password) is set up. */
    AVAILABLE,

    /** The hardware is fine but nothing is enrolled: no fingerprint/face and no screen lock. */
    NOT_ENROLLED,

    /** No usable authenticator (no hardware, temporarily unavailable, or unsupported). */
    UNAVAILABLE,
}

/** Why the prompt is being shown; the adapter picks the wording. */
enum class DeviceAuthPurpose {
    ENABLE_APP_LOCK,
    UNLOCK_APP,
}

sealed interface DeviceAuthResult {
    data object Success : DeviceAuthResult

    /** The user backed out, or the prompt was dismissed (rotation, app switched away). */
    data object Cancelled : DeviceAuthResult

    /** Too many wrong attempts, no host screen to show the prompt on, or a system error. */
    data object Failed : DeviceAuthResult
}

/**
 * Port for "prove you are the device owner" — implemented over `BiometricPrompt` (strong
 * biometric, or the PIN/pattern/password fallback) in `:app`, because the prompt needs a
 * foreground activity. Features and the domain see only this.
 */
interface DeviceAuthenticator {
    fun availability(): DeviceAuthAvailability

    suspend fun authenticate(purpose: DeviceAuthPurpose): DeviceAuthResult
}
