package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.entity.ConversationEntity
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.database.entity.MessageEntity
import com.postsaimanager.core.data.database.entity.MessageSourceEntity
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.data.util.PageImageStore
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.model.PageChange
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Deleting and reordering pages on the real schema: pages, evidence pages, chat citations and the page count move in one step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentPagesRoomTest {

    private lateinit var db: PamDatabase
    private lateinit var images: PageImageStore
    private lateinit var repo: DocumentRepositoryImpl

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, PamDatabase::class.java)
            .allowMainThreadQueries().build()
        images = mockk(relaxed = true)
        repo = DocumentRepositoryImpl(
            database = db, documentDao = db.documentDao(), conversationDao = db.conversationDao(), mapper = DocumentMapper(),
            fieldRevisionDao = db.fieldRevisionDao(), mergeExtraction = MergeExtractionUseCase(), pageImageStore = images,
            documentProcessor = mockk(relaxed = true), ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun close() = db.close()

    private fun page(n: Int) = DocumentPageEntity(
        id = "p$n", documentId = "d1", pageNumber = n, imagePath = "file:///page-$n.jpg", processedPath = null, ocrText = "text $n",
        ocrConfidence = 0.9f, width = 10, height = 10,
    )

    private fun field(id: String, page: Int?) = ExtractedDataEntity(
        id = id, documentId = "d1", fieldName = id, fieldValue = "v$id", fieldType = "TEXT", confidence = 0.9f, pageNumber = page,
    )

    private suspend fun seed() {
        db.documentDao().insert(
            DocumentEntity(
                id = "d1", title = "t", status = "EXTRACTED", documentType = null, language = null, sourceType = "CAMERA",
                thumbnailPath = null, pageCount = 3, createdAt = 1, modifiedAt = 1,
            ),
        )
        db.documentDao().insertPages(listOf(page(1), page(2), page(3)))
        db.documentDao().insertExtractedData(listOf(field("a", 1), field("b", 2), field("c", 3)))
        db.conversationDao().insert(
            ConversationEntity(id = "conv", documentId = "d1", aiModelId = null, modelType = "LOCAL", title = "t", lastMessageAt = 1, createdAt = 1),
        )
        db.conversationDao().insertMessage(
            MessageEntity(
                id = "m1", conversationId = "conv", role = "ASSISTANT", content = "x", mediaPath = null, toolCallId = null, toolName = null,
                toolArgs = null, toolResult = null, createdAt = 1,
            ),
        )
        db.conversationDao().insertMessageSources(listOf(MessageSourceEntity(messageId = "m1", documentId = "d1", pageNumber = 3, chunkId = "c3")))
    }

    private suspend fun pages() = db.documentDao().getPages("d1").map { it.id to it.pageNumber }
    private suspend fun fieldPages() = db.documentDao().getExtractedData("d1").associate { it.id to it.pageNumber }

    @Test
    fun `deleting a page removes it, renumbers the rest and moves the evidence and the citations`() = runBlocking {
        seed()

        val result = repo.changePages("d1", PageChange.Delete(2))

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(pages()).containsExactly("p1" to 1, "p3" to 2).inOrder()
        assertThat(fieldPages()).containsExactly("a", 1, "b", null, "c", 2)
        assertThat(db.conversationDao().getCitedPages("d1").single().pageNumber).isEqualTo(2)
        assertThat(db.documentDao().getById("d1")!!.pageCount).isEqualTo(2)
        coVerify { images.deleteImages(listOf("file:///page-2.jpg")) }
    }

    @Test
    fun `reordering renumbers the pages by the new order and moves what refers to them`() = runBlocking {
        seed()

        repo.changePages("d1", PageChange.Reorder(listOf(3, 1, 2)))

        assertThat(pages()).containsExactly("p3" to 1, "p1" to 2, "p2" to 3).inOrder()
        assertThat(fieldPages()).containsExactly("a", 2, "b", 3, "c", 1)
        assertThat(db.conversationDao().getCitedPages("d1").single().pageNumber).isEqualTo(1)
        assertThat(db.documentDao().getById("d1")!!.pageCount).isEqualTo(3)
        coVerify(exactly = 0) { images.deleteImages(any()) }
    }
}
