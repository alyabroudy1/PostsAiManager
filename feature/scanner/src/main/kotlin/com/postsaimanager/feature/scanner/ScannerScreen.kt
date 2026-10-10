package com.postsaimanager.feature.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.component.localizedMessage
import com.postsaimanager.core.designsystem.icon.PamIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onScanComplete: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The document that finished scanning is held here, rather than navigated to immediately,
    // while a notification-permission rationale is shown first — see the LaunchedEffect below.
    var pendingScanDocumentId by remember { mutableStateOf<String?>(null) }
    var showNotificationRationale by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // The grant/deny result itself doesn't change what happens next — either way, this
        // scan's document is ready to open, and the permission has been asked about once.
        viewModel.onNotificationPermissionResolved()
        pendingScanDocumentId?.let(onScanComplete)
        pendingScanDocumentId = null
    }

    // ML Kit Document Scanner options
    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }

    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    // Activity result launcher for scanner
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages?.mapNotNull { page ->
                page.imageUri
            } ?: emptyList()
            viewModel.onScanComplete(pages)
        } else {
            viewModel.onScanCancelled()
        }
    }

    // Auto-launch scanner when entering this screen
    LaunchedEffect(Unit) {
        scanner.getStartScanIntent(context as Activity)
            .addOnSuccessListener { intentSender ->
                viewModel.onScanLaunching()
                scannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener {
                viewModel.onScanLaunchFailed()
            }
    }

    // Navigate on success or on cancel — cancelling the scanner UI is not an error, it just
    // means there is nothing left to do on this screen but leave it.
    //
    // A first successful scan is the one moment this app has actually earned the right to ask
    // for POST_NOTIFICATIONS (API 33+): the document is now processing in the background, and
    // a notification is the only way to learn it finished without reopening the app. Asked
    // contextually rather than at launch, and gated three ways — API level, an existing grant
    // (a previous install, or the user enabling it in Settings after an earlier denial), and
    // the ViewModel's `offerNotificationPermission` (the DataStore-backed "have we ever
    // asked") — so it only ever interrupts navigation on the one scan where all three hold.
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is ScannerUiState.Success -> {
                val alreadyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (state.offerNotificationPermission && !alreadyGranted) {
                    pendingScanDocumentId = state.documentId
                    showNotificationRationale = true
                } else {
                    onScanComplete(state.documentId)
                }
            }
            is ScannerUiState.Cancelled -> onNavigateBack()
            else -> Unit
        }
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = {
                // Treated the same as "Not now" — dismissing without an answer still counts as
                // asked, so this dialog does not come back on the next scan either.
                showNotificationRationale = false
                viewModel.onNotificationPermissionResolved()
                pendingScanDocumentId?.let(onScanComplete)
                pendingScanDocumentId = null
            },
            title = { Text(stringResource(R.string.scanner_notify_title)) },
            text = { Text(stringResource(R.string.scanner_notify_text)) },
            confirmButton = {
                Button(onClick = {
                    showNotificationRationale = false
                    viewModel.onNotificationPermissionRequestLaunching()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.scanner_notify_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    viewModel.onNotificationPermissionResolved()
                    pendingScanDocumentId?.let(onScanComplete)
                    pendingScanDocumentId = null
                }) { Text(stringResource(R.string.scanner_notify_not_now)) }
            },
        )
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = stringResource(R.string.scanner_title),
                onNavigateBack = onNavigateBack,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "scanner_content",
            modifier = Modifier.padding(innerPadding),
        ) { state ->
            when (state) {
                is ScannerUiState.Idle -> PamEmptyState(
                    icon = PamIcons.Camera,
                    title = stringResource(R.string.scanner_idle_title),
                    subtitle = stringResource(R.string.scanner_idle_subtitle),
                )
                // Nothing to render — the LaunchedEffect above navigates back on the same
                // frame this state lands, so this is on screen for a fraction of a second.
                is ScannerUiState.Cancelled -> Unit
                is ScannerUiState.Processing -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(state.messageRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is ScannerUiState.Success -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = PamIcons.Documents,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.scanner_saved),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                is ScannerUiState.Error -> PamErrorState(
                    message = state.error.localizedMessage(context),
                    icon = PamIcons.Error,
                    retryLabel = stringResource(R.string.scanner_try_again),
                    onRetry = {
                        viewModel.resetState()
                        // Re-launch scanner
                        scanner.getStartScanIntent(context as Activity)
                            .addOnSuccessListener { intentSender ->
                                viewModel.onScanLaunching()
                                scannerLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                            .addOnFailureListener {
                                viewModel.onScanLaunchFailed()
                            }
                    },
                    secondaryLabel = stringResource(R.string.scanner_back),
                    onSecondary = onNavigateBack,
                )
            }
        }
    }
}
