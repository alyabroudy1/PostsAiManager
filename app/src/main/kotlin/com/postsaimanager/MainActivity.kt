package com.postsaimanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.postsaimanager.applock.AppLockGate
import com.postsaimanager.applock.BiometricDeviceAuthenticator
import com.postsaimanager.applock.bindSecureWindow
import com.postsaimanager.core.designsystem.theme.PamTheme
import com.postsaimanager.core.domain.applock.AppLockState
import com.postsaimanager.navigation.NotificationRouteInbox
import com.postsaimanager.navigation.PamApp
import dagger.hilt.android.AndroidEntryPoint
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

    @Inject
    lateinit var notificationRoutes: NotificationRouteInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only a fresh launch: a recreated activity (rotation) must not replay the tap.
        if (savedInstanceState == null) notificationRoutes.offer(intent?.dataString)

        authenticator.attach(this)
        // Before the first frame: the window is secure until the settings say the lock is off.
        bindSecureWindow(appLock)

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

    /** A notification tapped while the app runs. Kept in the inbox until the app lock is open and the navigation applies it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoutes.offer(intent.dataString)
    }

    /** The import screen shares the one authenticator and detaches it when it closes; the visible main screen takes it back. */
    override fun onStart() {
        super.onStart()
        authenticator.attach(this)
    }

    override fun onDestroy() {
        authenticator.detach(this)
        super.onDestroy()
    }
}
