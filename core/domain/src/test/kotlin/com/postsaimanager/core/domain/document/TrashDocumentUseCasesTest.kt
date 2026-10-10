package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.testing.FakeChatImageStore
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The trash lifecycle from the use-case side: move to trash hides a document from the normal
 * list and surfaces it in the trash; restore undoes that; a permanent delete/purge removes it
 * for good, respecting the cutoff. See documentation/07-document-pipeline.md, "Deleting
 * documents".
 */
class TrashDocumentUseCasesTest {

    private val repository = FakeDocumentRepository()
    private val moveToTrash = MoveDocumentToTrashUseCase(repository)
    private val restore = RestoreDocumentUseCase(repository)
    private val conversations = FakeConversationRepository()
    private val images = FakeChatImageStore()
    private val chatImages = DocumentChatImages(conversations, images)
    private val deletePermanently = DeleteDocumentPermanentlyUseCase(repository, chatImages)
    private val purge = PurgeExpiredDocumentsUseCase(repository, chatImages)

    private suspend fun chatWithPicture(documentId: String, conversationId: String) {
        conversations.createConversation(
            AiConversation(id = conversationId, documentId = documentId, aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
        )
        images.import(conversationId, "content://picture")
    }

    @Test
    @DisplayName("deleting a document for good deletes the pictures of its chat")
    fun deletePermanently_deletesChatPictures() = runTest {
        repository.seed(testDocument(id = "d1"))
        repository.seed(testDocument(id = "d2"))
        chatWithPicture("d1", "conv-d1")
        chatWithPicture("d2", "conv-d2")
        moveToTrash("d1")

        deletePermanently("d1")

        assertThat(images.stored).doesNotContainKey("conv-d1")
        assertThat(images.stored).containsKey("conv-d2")
    }

    @Test
    @DisplayName("purging deletes the chat pictures of expired documents only")
    fun purge_deletesChatPicturesOfExpiredOnly() = runTest {
        val now = System.currentTimeMillis()
        repository.seed(testDocument(id = "old", deletedAt = now - 40L * 24 * 60 * 60 * 1000))
        repository.seed(testDocument(id = "recent", deletedAt = now - 24L * 60 * 60 * 1000))
        chatWithPicture("old", "conv-old")
        chatWithPicture("recent", "conv-recent")

        purge()

        assertThat(images.stored).doesNotContainKey("conv-old")
        assertThat(images.stored).containsKey("conv-recent")
    }

    @Test
    @DisplayName("moving to trash hides a document from the normal list and puts it in the trash")
    fun moveToTrash_hidesFromListAndShowsInTrash() = runTest {
        repository.seed(testDocument(id = "d1"))

        moveToTrash("d1")

        assertThat(repository.getDocuments().first()).isEmpty()
        assertThat(repository.observeTrash().first().map { it.id }).containsExactly("d1")
    }

    @Test
    @DisplayName("restoring brings a trashed document back to the normal list")
    fun restore_bringsItBack() = runTest {
        repository.seed(testDocument(id = "d1"))
        moveToTrash("d1")

        restore("d1")

        assertThat(repository.getDocuments().first().map { it.id }).containsExactly("d1")
        assertThat(repository.observeTrash().first()).isEmpty()
    }

    @Test
    @DisplayName("deleting permanently removes a trashed document for good")
    fun deletePermanently_removesIt() = runTest {
        repository.seed(testDocument(id = "d1"))
        moveToTrash("d1")

        deletePermanently("d1")

        assertThat(repository.observeTrash().first()).isEmpty()
        assertThat(repository.getDocuments().first()).isEmpty()
    }

    @Test
    @DisplayName("purging respects the cutoff — only what's older than it is deleted")
    fun purge_respectsTheCutoff() = runTest {
        val now = System.currentTimeMillis()
        repository.seed(testDocument(id = "old", deletedAt = now - 40L * 24 * 60 * 60 * 1000)) // 40 days ago
        repository.seed(testDocument(id = "recent", deletedAt = now - 1L * 24 * 60 * 60 * 1000)) // 1 day ago

        purge() // default 30-day retention

        assertThat(repository.observeTrash().first().map { it.id }).containsExactly("recent")
    }
}
