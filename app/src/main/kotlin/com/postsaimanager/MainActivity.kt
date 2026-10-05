package com.postsaimanager

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.postsaimanager.applock.AppLockGate
import com.postsaimanager.applock.BiometricDeviceAuthenticator
import com.postsaimanager.core.designsystem.theme.PamTheme
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.navigation.PamApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A [FragmentActivity] (still a `ComponentActivity`, so `setContent` and edge-to-edge work as
 * before) because `BiometricPrompt` hosts its UI in a fragment.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLock: AppLockState

    @Inject
    lateinit var authenticator: BiometricDeviceAuthenticator

    @Inject
    lateinit var formFillingFlag: com.postsaimanager.core.model.FormFillingFlag

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        authenticator.attach(this)
        // Before the first frame: the window is secure until the settings say the lock is off.
        applySecureWindow(appLock.snapshot.value.secureWindow)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appLock.snapshot
                    .map { it.secureWindow }
                    .distinctUntilChanged()
                    .collect(::applySecureWindow)
            }
        }

        setContent {
            PamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppLockGate { PamApp(formFillingEnabled = formFillingFlag.enabled) }
                }
            }
        }
    }

    override fun onDestroy() {
        authenticator.detach(this)
        super.onDestroy()
    }

    /**
     * FLAG_SECURE blanks the app in the recents screen and blocks screenshots and screen
     * recording, which is what stops a locked household's letters showing up in the task
     * switcher. Dialogs and popups inherit it from this window.
     */
    private fun applySecureWindow(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
