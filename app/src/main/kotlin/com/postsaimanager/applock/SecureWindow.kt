package com.postsaimanager.applock

import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.postsaimanager.core.domain.applock.AppLockState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps this activity's window secure exactly while the app lock says so, for every activity that can show a letter.
 *
 * FLAG_SECURE blanks the app in the recents screen and blocks screenshots and screen recording, which is what stops a locked
 * household's letters showing up in the task switcher. Dialogs and popups inherit it from the window. Call it in `onCreate` before
 * the first frame: the window is secure until the settings say the lock is off.
 */
fun FragmentActivity.bindSecureWindow(appLock: AppLockState) {
    applySecure(appLock.snapshot.value.secureWindow)
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            appLock.snapshot
                .map { it.secureWindow }
                .distinctUntilChanged()
                .collect(::applySecure)
        }
    }
}

private fun FragmentActivity.applySecure(secure: Boolean) {
    if (secure) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
