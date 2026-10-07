package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [DocumentNoteRepository] for tests; ordered like the real one (pinned first, then the newest edit). A counter is the clock. */
class FakeDocumentNoteRepository : DocumentNoteRepository {

    private val all = MutableStateFlow<List<DocumentNote>>(emptyList())
    private var counter = 0
    private var clock = 0L

    /** Every note, in insertion order. */
    val snapshot: List<DocumentNote> get() = all.value

    fun seed(vararg notes: DocumentNote) {
        all.value = all.value + notes
    }

    private fun ordered(list: List<DocumentNote>, documentId: String) =
        list.filter { it.documentId == documentId }.sortedWith(compareByDescending<DocumentNote> { it.pinned }.thenByDescending { it.updatedAt })

    override fun observe(documentId: String): Flow<List<DocumentNote>> = all.map { ordered(it, documentId) }

    override suspend fun notes(documentId: String): List<DocumentNote> = ordered(all.value, documentId)

    override suspend fun add(documentId: String, text: String, source: NoteSource, sourceRef: String?): DocumentNote {
        val now = ++clock
        val note = DocumentNote("note-${counter++}", documentId, text, source, createdAt = now, updatedAt = now, sourceRef = sourceRef)
        all.value = all.value + note
        return note
    }

    override suspend fun upsertByRef(documentId: String, source: NoteSource, sourceRef: String, text: String): DocumentNote {
        val existing = all.value.firstOrNull { it.documentId == documentId && it.source == source && it.sourceRef == sourceRef }
            ?: return add(documentId, text, source, sourceRef)
        val updated = existing.copy(text = text, updatedAt = ++clock)
        all.value = all.value.map { if (it.id == existing.id) updated else it }
        return updated
    }

    override suspend fun updateText(id: String, text: String) {
        all.value = all.value.map { if (it.id == id) it.copy(text = text, updatedAt = ++clock) else it }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        all.value = all.value.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    override suspend fun delete(id: String) {
        all.value = all.value.filterNot { it.id == id }
    }

    override suspend fun deleteByRef(source: NoteSource, sourceRef: String) {
        all.value = all.value.filterNot { it.source == source && it.sourceRef == sourceRef }
    }
}
