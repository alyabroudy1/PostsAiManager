package com.postsaimanager.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.document.DeleteDocumentPermanentlyUseCase
import com.postsaimanager.core.domain.document.PurgeExpiredDocumentsUseCase
import com.postsaimanager.core.domain.document.RestoreDocumentUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Powers the "Recently deleted" screen — see documentation/07-document-pipeline.md. */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val restoreDocumentUseCase: RestoreDocumentUseCase,
    private val deletePermanentlyUseCase: DeleteDocumentPermanentlyUseCase,
) : ViewModel() {

    val uiState: StateFlow<TrashUiState> = documentRepository.observeTrash()
        .map { documents ->
            if (documents.isEmpty()) TrashUiState.Empty else TrashUiState.Success(documents)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState.Loading)

    fun restore(documentId: String) {
        viewModelScope.launch { restoreDocumentUseCase(documentId) }
    }

    fun deletePermanently(documentId: String) {
        viewModelScope.launch { deletePermanentlyUseCase(documentId) }
    }

    /** "Empty trash" — deletes every currently-trashed document permanently. */
    fun emptyTrash() {
        val current = uiState.value
        if (current !is TrashUiState.Success) return
        viewModelScope.launch {
            current.documents.forEach { document -> deletePermanentlyUseCase(document.id) }
        }
    }
}

sealed interface TrashUiState {
    data object Loading : TrashUiState
    data object Empty : TrashUiState
    data class Success(val documents: List<Document>) : TrashUiState
}

/** How many days remain before [document] is auto-purged, floored at 0. */
fun daysUntilPurge(document: Document): Long {
    val deletedAt = document.deletedAt ?: return PurgeExpiredDocumentsUseCase.DEFAULT_RETENTION_MS / DAY_MS
    val elapsed = System.currentTimeMillis() - deletedAt
    val remaining = PurgeExpiredDocumentsUseCase.DEFAULT_RETENTION_MS - elapsed
    return (remaining / DAY_MS).coerceAtLeast(0)
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
