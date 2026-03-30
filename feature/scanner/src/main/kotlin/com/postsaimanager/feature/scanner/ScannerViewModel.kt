package com.postsaimanager.feature.scanner

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    /**
     * Called when ML Kit Document Scanner returns scanned page URIs.
     */
    fun onScanComplete(pageUris: List<Uri>) {
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
                title = "Scanned ${pageUris.size} page(s)",
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
                    _uiState.value = ScannerUiState.Success(documentId)
                }
                is PamResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.error)
                }
            }
        }
    }

    fun onScanCancelled() {
        _uiState.value = ScannerUiState.Idle
    }

    fun resetState() {
        _uiState.value = ScannerUiState.Idle
    }
}

sealed interface ScannerUiState {
    data object Idle : ScannerUiState
    data class Processing(
        val message: String,
        val progress: Float,
    ) : ScannerUiState
    data class Success(val documentId: String) : ScannerUiState
    data class Error(val error: PamError) : ScannerUiState
}
