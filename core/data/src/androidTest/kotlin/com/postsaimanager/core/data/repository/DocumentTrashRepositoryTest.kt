package com.postsaimanager.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.ConversationEntity
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.MessageEntity
import com.postsaimanager.core.data.database.entity.MessageSourceEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.data.util.PageImageStore
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.ExtractionResult
import com.postsaimanager.core.model.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the trash lifecycle (moveToTrash / restore / deletePermanently / purgeTrashOlderThan)
 * against a real in-memory Room database — `deletePermanently`'s ordering and its
 * `database.withTransaction` block are not meaningfully testable against mocks (see
 * documentation/07-document-pipeline.md, "Deleting documents"), so this uses the real thing
 * rather than `:core:testing`'s in-memory fake, which models the repository's *contract*, not
 * Room's cascades.
 */
@RunWith(AndroidJUnit4::class)
class DocumentTrashRepositoryTest {

    private lateinit var db: PamDatabase
    private lateinit var repository: DocumentRepositoryImpl
    private val cancelledIds = mutableListOf<String>()

    private inner class NoOpDocumentProcessor : DocumentProcessor {
        override val processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
        override suspend fun processDocument(documentId: String, reprocess: Boolean, forcedFamily: String?): PamResult<ExtractionResult> =
            PamResult.Success(ExtractionResult(documentId = documentId, language = null, fields = emptyList()))
        override suspend fun enqueueReprocess(documentId: String, urgent: Boolean) = Unit
        override suspend fun enqueue(documentId: String, force: Boolean, forcedFamily: String?) = Unit
        override fun cancel(documentId: String) { cancelledIds += documentId }
    }

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PamDatabase::class.java).build()
        repository = DocumentRepositoryImpl(
            database = db,
            documentDao = db.documentDao(),
            conversationDao = db.conversationDao(),
            mapper = DocumentMapper(),
            fieldRevisionDao = db.fieldRevisionDao(),
            mergeExtraction = MergeExtractionUseCase(),
            pageImageStore = PageImageStore(context, Dispatchers.IO),
            documentProcessor = NoOpDocumentProcessor(),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun document(id: String) = DocumentEntity(
        id = id,
        title = "Doc $id",
        status = "EXTRACTED",
        documentType = null,
        language = null,
        sourceType = "CAMERA",
        thumbnailPath = null,
        pageCount = 1,
        createdAt = 0L,
        modifiedAt = 0L,
    )

    @Test
    fun moveToTrash_hidesFromTheListButKeepsTheRow() = runBlocking {
        db.documentDao().insert(document("d1"))

        repository.moveToTrash("d1")

        assertTrue(repository.getDocuments().first().isEmpty())
        assertNotNull(db.documentDao().getById("d1"))
        assertTrue(db.documentDao().getById("d1")!!.deletedAt != null)
        assertEquals(listOf("d1"), cancelledIds)
    }

    @Test
    fun restore_bringsItBackToTheList() = runBlocking {
        db.documentDao().insert(document("d1"))
        repository.moveToTrash("d1")

        repository.restore("d1")

        val docs = repository.getDocuments().first()
        assertEquals(listOf("d1"), docs.map { it.id })
    }

    @Test
    fun deletePermanently_removesRowConversationAndForeignMessageSources() = runBlocking {
        db.documentDao().insert(document("d1"))

        // The document's own conversation.
        db.conversationDao().insert(
            ConversationEntity(
                id = "conv-d1",
                documentId = "d1",
                aiModelId = "model",
                modelType = "LOCAL",
                title = "Doc d1",
                lastMessageAt = 0L,
                createdAt = 0L,
            ),
        )
        // The all-documents chat, with a message that cites d1 — message_sources.documentId
        // carries no FK (see MessageSourceEntity), so this only goes away if the repository
        // sweeps it explicitly.
        db.conversationDao().insert(
            ConversationEntity(
                id = "conv-standalone",
                documentId = null,
                aiModelId = "model",
                modelType = "LOCAL",
                title = "All documents",
                lastMessageAt = 0L,
                createdAt = 0L,
            ),
        )
        db.conversationDao().insertMessage(
            MessageEntity(
                id = "m1",
                conversationId = "conv-standalone",
                role = "ASSISTANT",
                content = "cites d1",
                mediaPath = null,
                toolCallId = null,
                toolName = null,
                toolArgs = null,
                toolResult = null,
                createdAt = 0L,
            ),
        )
        db.conversationDao().insertMessageSources(
            listOf(MessageSourceEntity(messageId = "m1", documentId = "d1", pageNumber = 1, chunkId = "c1")),
        )

        repository.deletePermanently("d1")

        assertNull(db.documentDao().getById("d1"))
        assertNull(db.conversationDao().getById("conv-d1"))
        assertNotNull(db.conversationDao().getById("conv-standalone")) // untouched
        assertEquals(listOf("d1"), cancelledIds)
    }

    @Test
    fun purgeTrashOlderThan_onlyPurgesWhatsPastTheCutoff() = runBlocking {
        db.documentDao().insert(document("old"))
        db.documentDao().insert(document("recent"))
        db.documentDao().setDeletedAt("old", 1_000L)
        db.documentDao().setDeletedAt("recent", 9_000L)

        val purged = repository.purgeTrashOlderThan(5_000L)

        assertEquals(PamResult.Success(1), purged)
        assertNull(db.documentDao().getById("old"))
        assertNotNull(db.documentDao().getById("recent"))
    }
}
