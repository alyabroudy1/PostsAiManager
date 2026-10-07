package com.postsaimanager.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.designsystem.component.NoteActions
import com.postsaimanager.core.domain.memory.AddProfileNoteUseCase
import com.postsaimanager.core.domain.memory.DeleteDocumentNoteUseCase
import com.postsaimanager.core.domain.memory.EditDocumentNoteUseCase
import com.postsaimanager.core.domain.memory.ObserveProfileNotesUseCase
import com.postsaimanager.core.domain.memory.PinDocumentNoteUseCase
import com.postsaimanager.core.model.DocumentNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The notes of the household person on screen ("What the assistant remembers", written by the chat about all documents) and what the
 * user may do with them. Edit, delete and pin are the notes' own use cases, the same ones a document's notes use: a note is a note
 * whoever it is about.
 */
@HiltViewModel
class ProfileNotesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeNotes: ObserveProfileNotesUseCase,
    private val addNote: AddProfileNoteUseCase,
    private val editNote: EditDocumentNoteUseCase,
    private val deleteNote: DeleteDocumentNoteUseCase,
    private val pinNote: PinDocumentNoteUseCase,
) : ViewModel() {

    private val profileId: String = savedStateHandle.get<String>(ProfileDetailViewModel.ARG_PROFILE_ID).orEmpty()

    val notes: StateFlow<List<DocumentNote>> = observeNotes(profileId)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val actions = NoteActions(
        add = { text -> viewModelScope.launch { addNote(profileId, text) } },
        edit = { id, text -> viewModelScope.launch { editNote(id, text) } },
        delete = { id -> viewModelScope.launch { deleteNote(id) } },
        pin = { id, pinned -> viewModelScope.launch { pinNote(id, pinned) } },
    )
}
