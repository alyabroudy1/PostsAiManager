package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.flow.Flow

/**
 * The durable notes of a document ("What the assistant remembers"). Notes are listed pinned first, then the newest edit first, so
 * that a reader that takes the top of the list takes the notes that matter most.
 */
interface DocumentNoteRepository {

    fun observe(documentId: String): Flow<List<DocumentNote>>

    suspend fun notes(documentId: String): List<DocumentNote>

    /** The notes of the household person [profileId], in the same order. */
    fun observeForProfile(profileId: String): Flow<List<DocumentNote>>

    /**
     * The notes of the all-documents chat: those of every person and the household-wide ones (every note that is not about a
     * document), in the same order.
     */
    fun observeOutsideDocuments(): Flow<List<DocumentNote>>

    suspend fun notesOutsideDocuments(): List<DocumentNote>

    /** Adds a note of the all-documents chat: about the household person [profileId], or household-wide when it is null. */
    suspend fun addOutsideDocument(profileId: String?, text: String, source: NoteSource, sourceRef: String? = null): DocumentNote

    /** Adds a note; [sourceRef] identifies what it came from (null for one the user typed). */
    suspend fun add(documentId: String, text: String, source: NoteSource, sourceRef: String? = null): DocumentNote

    /**
     * Writes the note of ([documentId], [source], [sourceRef]): replaces its text when it exists (keeping its id, creation time and
     * pin), adds it otherwise. Idempotent: the same call twice leaves one note.
     */
    suspend fun upsertByRef(documentId: String, source: NoteSource, sourceRef: String, text: String): DocumentNote

    /** Replaces the text of note [id]; the note becomes the user's (source USER) and keeps its reference. No-op for an unknown id. */
    suspend fun updateText(id: String, text: String)

    suspend fun setPinned(id: String, pinned: Boolean)

    suspend fun delete(id: String)

    /** Removes the note of ([source], [sourceRef]), if any. */
    suspend fun deleteByRef(source: NoteSource, sourceRef: String)
}
