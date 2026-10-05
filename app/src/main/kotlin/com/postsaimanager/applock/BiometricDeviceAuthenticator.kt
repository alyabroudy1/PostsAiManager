package com.postsaimanager.applock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.postsaimanager.R
import com.postsaimanager.core.domain.applock.DeviceAuthAvailability
import com.postsaimanager.core.domain.applock.DeviceAuthPurpose
import com.postsaimanager.core.domain.applock.DeviceAuthResult
import com.postsaimanager.core.domain.applock.DeviceAuthenticator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [DeviceAuthenticator] over `androidx.biometric.BiometricPrompt`: a strong biometric, with the
 * screen-lock PIN/pattern/password as the fallback the prompt offers itself.
 *
 * `BiometricPrompt` must be hosted by a foreground `FragmentActivity`, so `MainActivity` attaches
 * itself here in `onCreate` and detaches in `onDestroy`. Everything below runs on the main thread
 * (callers are ViewModels on `viewModelScope`), so the plain fields need no locking.
 *
 * **Rotation.** The prompt's callbacks are bound to the activity that created it; when that
 * activity is destroyed the pending prompt is cancelled and its caller sees
 * [DeviceAuthResult.Cancelled]. The lock screen asks again once the new activity resumes, so a
 * rotation costs one re-prompt instead of a coroutine that never returns.
 */
@Singleton
class BiometricDeviceAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceAuthenticator {

    private var host: WeakReference<FragmentActivity>? = null
    private var finishPending: ((DeviceAuthResult) -> Unit)? = null
    private var pendingPrompt: BiometricPrompt? = null

    fun attach(activity: FragmentActivity) {
        host = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (host?.get() !== activity) return
        host = null
        pendingPrompt?.cancelAuthentication()
        finishPending?.invoke(DeviceAuthResult.Cancelled)
    }

    override fun availability(): DeviceAuthAvailability =
        when (BiometricManager.from(context).canAuthenticate(allowedAuthenticators())) {
            BiometricManager.BIOMETRIC_SUCCESS -> DeviceAuthAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> DeviceAuthAvailability.NOT_ENROLLED
            else -> DeviceAuthAvailability.UNAVAILABLE
        }

    override suspend fun authenticate(purpose: DeviceAuthPurpose): DeviceAuthResult {
        val activity = host?.get() ?: return DeviceAuthResult.Failed
        if (finishPending != null) return DeviceAuthResult.Cancelled

        return suspendCancellableCoroutine { continuation ->
            val finish: (DeviceAuthResult) -> Unit = { result ->
                finishPending = null
                pendingPrompt = null
                if (continuation.isActive) continuation.resume(result)
            }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                        finish(DeviceAuthResult.Success)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                        finish(
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED,
                                -> DeviceAuthResult.Cancelled
                                else -> DeviceAuthResult.Failed
                            },
                        )

                    // A wrong finger: the prompt stays up and lets the user try again.
                    override fun onAuthenticationFailed() = Unit
                },
            )
            finishPending = finish
            pendingPrompt = prompt
            continuation.invokeOnCancellation {
                prompt.cancelAuthentication()
                finishPending = null
                pendingPrompt = null
            }
            prompt.authenticate(promptInfo(purpose))
        }
    }

    private fun promptInfo(purpose: DeviceAuthPurpose): BiometricPrompt.PromptInfo {
        val (title, subtitle) = when (purpose) {
            DeviceAuthPurpose.ENABLE_APP_LOCK ->
                R.string.applock_prompt_enable_title to R.string.applock_prompt_enable_subtitle
            DeviceAuthPurpose.UNLOCK_APP ->
                R.string.applock_prompt_unlock_title to R.string.applock_prompt_unlock_subtitle
        }
        // No negative button: it is not allowed together with DEVICE_CREDENTIAL, and the
        // system's own back/outside-tap dismissal already reports ERROR_USER_CANCELED.
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(title))
            .setSubtitle(context.getString(subtitle))
            .setAllowedAuthenticators(allowedAuthenticators())
            .setConfirmationRequired(false)
            .build()
    }

    private companion object {
        /**
         * BIOMETRIC_STRONG | DEVICE_CREDENTIAL is the combination we want, but the platform
         * does not support it on API 28-29; BIOMETRIC_WEAK | DEVICE_CREDENTIAL is the closest
         * that is. (Android 11+ and pre-28 both accept the strong combination.)
         */
        fun allowedAuthenticators(): Int =
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.Q) {
                BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            } else {
                BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            }
    }
}
