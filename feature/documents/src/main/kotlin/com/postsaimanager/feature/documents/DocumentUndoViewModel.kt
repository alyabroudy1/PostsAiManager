package com.postsaimanager.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.document.RestoreDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores a just-deleted document from an app-level Undo snackbar. Scoped to the host
 * activity (obtained in `PamApp`), so it is still alive after the detail screen's own
 * ViewModel has been cleared by navigating back.
 */
@HiltViewModel
class DocumentUndoViewModel @Inject constructor(
    private val restoreDocumentUseCase: RestoreDocumentUseCase,
) : ViewModel() {
    fun restore(documentId: String) {
        viewModelScope.launch { restoreDocumentUseCase(documentId) }
    }
}
