package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.memory.AddDocumentNoteUseCase
import com.postsaimanager.core.domain.memory.DeleteDocumentNoteUseCase
import com.postsaimanager.core.domain.memory.EditDocumentNoteUseCase
import com.postsaimanager.core.domain.memory.ObserveDocumentNotesUseCase
import com.postsaimanager.core.domain.memory.PinDocumentNoteUseCase
import com.postsaimanager.core.model.DocumentNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the card "What the assistant remembers" does with a note. */
data class NoteActions(
    val add: (text: String) -> Unit = {},
    val edit: (id: String, text: String) -> Unit = { _, _ -> },
    val delete: (id: String) -> Unit = {},
    val pin: (id: String, pinned: Boolean) -> Unit = { _, _ -> },
)

/** The notes of the document on screen ("What the assistant remembers") and what the user may do with them. */
@HiltViewModel
class DocumentNotesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeNotes: ObserveDocumentNotesUseCase,
    private val addNote: AddDocumentNoteUseCase,
    private val editNote: EditDocumentNoteUseCase,
    private val deleteNote: DeleteDocumentNoteUseCase,
    private val pinNote: PinDocumentNoteUseCase,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    val notes: StateFlow<List<DocumentNote>> = observeNotes(documentId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val actions = NoteActions(
        add = { text -> viewModelScope.launch { addNote(documentId, text) } },
        edit = { id, text -> viewModelScope.launch { editNote(id, text) } },
        delete = { id -> viewModelScope.launch { deleteNote(id) } },
        pin = { id, pinned -> viewModelScope.launch { pinNote(id, pinned) } },
    )
}
