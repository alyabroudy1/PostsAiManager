package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The document notes on the real schema (in-memory Room, foreign keys on): the repository, its order, one note per reference, the cascade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentNoteRepositoryRoomTest {

    private lateinit var db: PamDatabase
    private lateinit var notes: DocumentNoteRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        notes = DocumentNoteRepositoryImpl(db.documentNoteDao(), Dispatchers.Unconfined)
        runBlocking { listOf("d1", "d2").forEach { db.documentDao().insert(document(it)) } }
    }

    @After
    fun close() = db.close()

    private fun document(id: String) = DocumentEntity(
        id = id, title = "Letter", status = "COMPLETED", documentType = null, language = null, sourceType = "SCAN",
        thumbnailPath = null, pageCount = 1, createdAt = 1, modifiedAt = 1,
    )

    private fun texts(documentId: String = "d1") = runBlocking { notes.notes(documentId).map { it.text } }

    @Test
    fun `a note is added and read back with its source and reference`() = runBlocking<Unit> {
        val added = notes.add("d1", "Already paid", NoteSource.AI, "m1")

        val read = notes.notes("d1").single()
        assertThat(read).isEqualTo(added)
        assertThat(read.source).isEqualTo(NoteSource.AI)
        assertThat(read.sourceRef).isEqualTo("m1")
        assertThat(read.pinned).isFalse()
    }

    @Test
    fun `notes are per document, pinned first, then the newest edit`() = runBlocking<Unit> {
        notes.add("d1", "first", NoteSource.USER)
        Thread.sleep(3)
        notes.add("d1", "second", NoteSource.USER)
        Thread.sleep(3)
        notes.add("d1", "third", NoteSource.USER)
        notes.add("d2", "other document", NoteSource.USER)
        assertThat(texts()).containsExactly("third", "second", "first").inOrder()

        notes.setPinned(notes.notes("d1").last().id, true)

        assertThat(texts()).containsExactly("first", "third", "second").inOrder()
        assertThat(notes.observe("d1").first().map { it.text }).containsExactly("first", "third", "second").inOrder()
    }

    @Test
    fun `upsert by reference keeps one note per card and keeps its id, creation and pin`() = runBlocking<Unit> {
        val first = notes.upsertByRef("d1", NoteSource.ACTION, "card-1", "Email opened on 7 Oct")
        notes.setPinned(first.id, true)

        val again = notes.upsertByRef("d1", NoteSource.ACTION, "card-1", "Email opened on 8 Oct")
        notes.upsertByRef("d1", NoteSource.ACTION, "card-2", "Reminder set")

        assertThat(again.id).isEqualTo(first.id)
        assertThat(again.createdAt).isEqualTo(first.createdAt)
        assertThat(notes.notes("d1")).hasSize(2)
        val kept = notes.notes("d1").first { it.id == first.id }
        assertThat(kept.text).isEqualTo("Email opened on 8 Oct")
        assertThat(kept.pinned).isTrue()
    }

    @Test
    fun `editing keeps the source, deleting removes, and an unknown id is a no-op`() = runBlocking<Unit> {
        val note = notes.add("d1", "from the model", NoteSource.AI, "m1")

        notes.updateText(note.id, "corrected")
        notes.updateText("missing", "nothing")
        notes.setPinned("missing", true)

        assertThat(notes.notes("d1").single().text).isEqualTo("corrected")
        assertThat(notes.notes("d1").single().source).isEqualTo(NoteSource.AI)
        notes.delete(note.id)
        assertThat(notes.notes("d1")).isEmpty()
    }

    @Test
    fun `deleting by reference removes that card's note only`() = runBlocking<Unit> {
        notes.upsertByRef("d1", NoteSource.ACTION, "card-1", "one")
        notes.upsertByRef("d1", NoteSource.ACTION, "card-2", "two")
        notes.add("d1", "same ref, other source", NoteSource.AI, "card-1")

        notes.deleteByRef(NoteSource.ACTION, "card-1")

        assertThat(texts()).containsExactly("two", "same ref, other source")
    }

    @Test
    fun `notes go with their document`() = runBlocking<Unit> {
        notes.add("d1", "gone with it", NoteSource.USER)
        notes.add("d2", "stays", NoteSource.USER)

        db.documentDao().deleteById("d1")

        assertThat(texts("d1")).isEmpty()
        assertThat(texts("d2")).containsExactly("stays")
    }
}
