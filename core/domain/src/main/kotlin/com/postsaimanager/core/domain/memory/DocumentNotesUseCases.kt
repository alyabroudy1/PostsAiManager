package com.postsaimanager.core.domain.memory

import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** How a note's text is kept: one line of single spaces, at most [MAX_CHARS] characters; null when nothing is left. */
object DocumentNoteText {

    const val MAX_CHARS = 300

    fun clean(text: String): String? = text.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS).trim().takeIf { it.isNotEmpty() }
}

/**
 * The notes of a document as the context builder reads them: already formatted ([DocumentMemoryFormat]) and capped, and current
 * (every change of the notes gives the text again). An empty string means there is nothing to remember.
 */
class ObserveDocumentMemoryUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    operator fun invoke(documentId: String): Flow<String> = notes.observe(documentId).map(DocumentMemoryFormat::format).distinctUntilChanged()
}

/** The notes of a document as the user sees them in "What the assistant remembers", pinned first, then the newest. */
class ObserveDocumentNotesUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    operator fun invoke(documentId: String): Flow<List<DocumentNote>> = notes.observe(documentId)
}

/** "Add a note": a note the user typed. Returns false (nothing written) when the text is empty. */
class AddDocumentNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(documentId: String, text: String): Boolean {
        val clean = DocumentNoteText.clean(text) ?: return false
        notes.add(documentId, clean, NoteSource.USER)
        return true
    }
}

/** Edits a note's text. An emptied text deletes the note (the user cleared it). */
class EditDocumentNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(id: String, text: String) {
        val clean = DocumentNoteText.clean(text)
        if (clean == null) notes.delete(id) else notes.updateText(id, clean)
    }
}

class DeleteDocumentNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(id: String) = notes.delete(id)
}

class PinDocumentNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(id: String, pinned: Boolean) = notes.setPinned(id, pinned)
}

/**
 * The mechanical note of a confirmed action card (no AI): the card's state change is the fact. One note per card ([cardId] is the
 * note's reference), so recording the same card again replaces its text and never adds a second note.
 */
class RecordActionNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(documentId: String, cardId: String, text: String) {
        val clean = DocumentNoteText.clean(text) ?: return
        notes.upsertByRef(documentId, NoteSource.ACTION, cardId, clean)
    }
}

/** Removes the note of a card that is no longer confirmed (a restored card). A card that never had one is a no-op. */
class ForgetActionNoteUseCase @Inject constructor(
    private val notes: DocumentNoteRepository,
) {
    suspend operator fun invoke(cardId: String) = notes.deleteByRef(NoteSource.ACTION, cardId)
}
