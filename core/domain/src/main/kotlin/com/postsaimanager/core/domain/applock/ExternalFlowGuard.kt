package com.postsaimanager.core.domain.applock

/** Proof that an app-initiated external flow is under way; see [ExternalFlowGuard]. */
class ExternalFlowToken internal constructor(
    internal val id: Long,
    /** Only for diagnostics ("scanner", "notification-permission"); never shown to users. */
    val reason: String,
)

/**
 * The port features use to say "I am about to send the user out of our activity on purpose and
 * they will come back": the ML Kit scanner, a runtime permission dialog, the system's
 * enrolment settings, the share sheet, a calendar insert, a payment app. Without it the app
 * lock would treat that trip as any other background and, at timeout 0 or after a long scan,
 * lock the user out of their own flow on return.
 *
 * ### The pattern for a new flow
 *
 * 1. Call [expect] immediately **before** launching the intent or dialog, and keep the token
 *    (ViewModel field is fine).
 * 2. Call [finish] in the flow's result callback, or when the launch fails — that is what ends
 *    the protection for flows that never took the app to the background (a permission dialog
 *    only pauses the activity).
 * 3. If the app does go to the background and comes back, the return itself consumes the
 *    token; calling [finish] afterwards is harmless.
 *
 * The protection is bounded: a token stops counting [AppLockState.EXTERNAL_FLOW_GRACE_MINUTES]
 * minutes after [expect], so a scanner left open for hours still locks the app on return.
 * Flows may nest; each has its own token.
 */
interface ExternalFlowGuard {
    fun expect(reason: String): ExternalFlowToken

    fun finish(token: ExternalFlowToken?)
}
