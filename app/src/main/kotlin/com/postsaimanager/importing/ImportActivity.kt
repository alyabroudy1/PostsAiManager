package com.postsaimanager.importing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.MainActivity
import com.postsaimanager.applock.AppLockGate
import com.postsaimanager.applock.BiometricDeviceAuthenticator
import com.postsaimanager.applock.bindSecureWindow
import com.postsaimanager.core.common.notification.NotificationRoute
import com.postsaimanager.core.designsystem.theme.PamTheme
import com.postsaimanager.core.domain.applock.AppLockState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where a shared PDF or image, an "Open with" PDF and the Home import button end up: the confirm sheet, then the background import.
 *
 * Everything sits behind the app lock. [AppLockGate] composes its content only after unlock, and the files are read (copied, hashed,
 * rendered) from that content, so nothing of a shared letter is touched while the app is locked. The intent's URIs are only
 * remembered here until then.
 */
@AndroidEntryPoint
class ImportActivity : FragmentActivity() {

    @Inject
    lateinit var appLock: AppLockState

    @Inject
    lateinit var authenticator: BiometricDeviceAuthenticator

    private val viewModel: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        authenticator.attach(this)
        bindSecureWindow(appLock)

        val uris = urisOf(intent)
        setContent {
            PamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppLockGate {
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        // The first time this is composed is after the unlock; the view model starts only once.
                        LaunchedEffect(Unit) { viewModel.start(uris) }
                        LaunchedEffect(state.target) { state.target?.let(::leave) }
                        ImportSheet(
                            state = state,
                            onCancel = viewModel::cancel,
                            onConfirm = viewModel::confirm,
                            onSeparateChange = viewModel::setEachImageSeparate,
                            onAddAgainChange = { row, add -> viewModel.setAddAgain(row.group, add) },
                            onUnlock = viewModel::unlock,
                            onHide = viewModel::hide,
                            onClose = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        authenticator.attach(this)
    }

    override fun onDestroy() {
        authenticator.detach(this)
        super.onDestroy()
    }

    /** To the new document, or to the list for several; or just away when nothing was imported. */
    private fun leave(target: ImportTarget) {
        val route = when (target) {
            is ImportTarget.OpenDocument -> NotificationRoute.Document(target.documentId)
            ImportTarget.OpenList -> NotificationRoute.Documents
            // The sheet shows the failure and its own Close button.
            ImportTarget.Failed -> return
            ImportTarget.Close -> null
        }
        if (route != null) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(route.toUri()))
                    .setClass(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        /**
         * An intent that opens the import sheet for files this app already holds a read grant on (the Home picker's result). The
         * grant is passed on through the clip.
         */
        fun intentFor(context: Context, uris: List<Uri>): Intent {
            val intent = Intent(context, ImportActivity::class.java)
                .setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
            }
            return intent
        }

        @Suppress("DEPRECATION")
        private fun urisOf(intent: Intent): List<String> {
            val single = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val many = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            val clip = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri?.toString() } }.orEmpty()
            return ImportSources.from(
                action = intent.action,
                stream = single?.toString(),
                streams = many?.map { it.toString() }.orEmpty(),
                clip = clip,
                data = intent.dataString,
            )
        }
    }
}
