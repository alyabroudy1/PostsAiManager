package com.postsaimanager.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.core.domain.applock.DeviceAuthAvailability
import com.postsaimanager.core.domain.applock.DeviceAuthPurpose
import com.postsaimanager.core.domain.applock.DeviceAuthResult
import com.postsaimanager.core.domain.applock.DeviceAuthenticator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the lock screen; the state itself is owned by [AppLockState]. */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val appLock: AppLockState,
    private val authenticator: DeviceAuthenticator,
) : ViewModel() {

    val state: StateFlow<AppLockState.Snapshot> = appLock.snapshot

    private var prompting = false

    /** Shows the prompt (once at a time) and unlocks on success. */
    fun unlock() {
        if (prompting) return
        // The device lost its screen lock since the app lock was turned on. Nothing is left to
        // prove: anyone can open the phone itself, and refusing here would lock the household
        // out of its own letters for good. The setting stays on and applies again as soon as a
        // screen lock exists.
        if (authenticator.availability() == DeviceAuthAvailability.NOT_ENROLLED) {
            appLock.unlock()
            return
        }
        prompting = true
        viewModelScope.launch {
            try {
                if (authenticator.authenticate(DeviceAuthPurpose.UNLOCK_APP) == DeviceAuthResult.Success) {
                    appLock.unlock()
                }
            } finally {
                prompting = false
            }
        }
    }
}
