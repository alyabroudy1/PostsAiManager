package com.postsaimanager.feature.scanner

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.DocumentTitleCodes
import com.postsaimanager.core.model.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val documentProcessor: DocumentProcessor,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val externalFlowGuard: ExternalFlowGuard,
) : ViewModel() {

    private var scanFlow: ExternalFlowToken? = null
    private var permissionFlow: ExternalFlowToken? = null

    /**
     * Called just before ML Kit's scanner takes over the screen, so coming back from it — however
     * long the scan took, up to the guard's grace window — does not trigger the app lock.
     */
    fun onScanLaunching() {
        externalFlowGuard.finish(scanFlow)
        scanFlow = externalFlowGuard.expect("document-scanner")
    }

    /** Called just before the OS notification-permission dialog is shown. */
    fun onNotificationPermissionRequestLaunching() {
        externalFlowGuard.finish(permissionFlow)
        permissionFlow = externalFlowGuard.expect("notification-permission")
    }

    override fun onCleared() {
        externalFlowGuard.finish(scanFlow)
        externalFlowGuard.finish(permissionFlow)
    }

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    /**
     * Called when ML Kit Document Scanner returns scanned page URIs.
     */
    fun onScanComplete(pageUris: List<Uri>) {
        finishScanFlow()
        if (pageUris.isEmpty()) {
            _uiState.value = ScannerUiState.Error(PamError.ScanCancelled())
            return
        }

        _uiState.value = ScannerUiState.Processing(
            message = "Creating document...",
            progress = 0f,
        )

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val documentId = UuidGenerator.generate()

            val pages = pageUris.mapIndexed { index, uri ->
                DocumentPage(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    pageNumber = index + 1,
                    imagePath = uri.toString(),
                    width = 0,
                    height = 0,
                )
            }

            val document = Document(
                id = documentId,
                // The English text is only the fallback; the code and count are what the UI renders
                // in the user's language, until extraction gives the document a real title.
                title = "Scanned ${pageUris.size} page(s)",
                titleCode = DocumentTitleCodes.SCANNED_PAGES,
                titleArgs = listOf(pageUris.size.toString()),
                status = DocumentStatus.NEW,
                sourceType = SourceType.CAMERA,
                pageCount = pageUris.size,
                createdAt = now,
                modifiedAt = now,
            )

            _uiState.value = ScannerUiState.Processing(
                message = "Saving document...",
                progress = 0.5f,
            )

            when (val result = documentRepository.createDocument(document, pages)) {
                is PamResult.Success -> {
                    // A scanned document is not searchable until it is processed
                    // (documentation/07-document-pipeline.md §7) — enqueue it before
                    // navigating away, so it starts reading itself immediately rather than
                    // waiting for someone to open it.
                    documentProcessor.enqueue(documentId)
                    // The natural moment to ask for POST_NOTIFICATIONS (API 33+): right after
                    // the first thing that would actually benefit from it — a scan that is now
                    // processing in the background — rather than on first app launch, before
                    // the user has any reason to care. `!requested` is the whole "once" in "ask
                    // once": the Composable still checks the OS permission itself (a grant from
                    // a previous install, or API < 33) before showing anything.
                    val alreadyRequested = userPreferencesRepository.getUserPreferences()
                        .first().notificationPermissionRequested
                    _uiState.value = ScannerUiState.Success(
                        documentId = documentId,
                        offerNotificationPermission = !alreadyRequested,
                    )
                }
                is PamResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.error)
                }
            }
        }
    }

    /**
     * The notification permission prompt (rationale, then the OS dialog, or neither when it
     * doesn't apply) has run its course — called exactly once per scan, from `ScannerScreen`,
     * whichever way it was resolved. A denial is as final as a grant: either way, nagging again
     * on the next scan would be the nag this task exists to avoid.
     */
    fun onNotificationPermissionResolved() {
        externalFlowGuard.finish(permissionFlow)
        permissionFlow = null
        viewModelScope.launch { userPreferencesRepository.setNotificationPermissionRequested(true) }
    }

    /** The user dismissed ML Kit's scanner UI without scanning anything — not an error. */
    fun onScanCancelled() {
        finishScanFlow()
        _uiState.value = ScannerUiState.Cancelled
    }

    private fun finishScanFlow() {
        externalFlowGuard.finish(scanFlow)
        scanFlow = null
    }

    /** The scanner intent itself could not be launched (e.g. Play Services unavailable). */
    fun onScanLaunchFailed() {
        finishScanFlow()
        _uiState.value = ScannerUiState.Error(PamError.ScannerUnavailable())
    }

    fun resetState() {
        _uiState.value = ScannerUiState.Idle
    }
}

sealed interface ScannerUiState {
    data object Idle : ScannerUiState
    data object Cancelled : ScannerUiState
    data class Processing(
        val message: String,
        val progress: Float,
    ) : ScannerUiState
    data class Success(
        val documentId: String,
        /** Whether `ScannerScreen` should offer the POST_NOTIFICATIONS rationale before
         * navigating away — see [ScannerViewModel.onScanComplete]. */
        val offerNotificationPermission: Boolean = false,
    ) : ScannerUiState
    data class Error(val error: PamError) : ScannerUiState
}
