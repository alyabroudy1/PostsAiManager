package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ChatImageStore
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Moves a document to the trash — the everyday "delete" action from the documents list, the
 * detail screen's overflow menu, and the FAILED no-pages banner. See
 * `DocumentRepository.moveToTrash` and documentation/07-document-pipeline.md, "Deleting
 * documents". Renamed from a hard delete (now [DeleteDocumentPermanentlyUseCase]) so every
 * "Delete" entry point in the app is undoable by default.
 */
class MoveDocumentToTrashUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(documentId: String): PamResult<Unit> =
        documentRepository.moveToTrash(documentId)
}

/** Restores a trashed document — the "Undo" on the trash snackbar, and the trash screen's Restore. */
class RestoreDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(documentId: String): PamResult<Unit> =
        documentRepository.restore(documentId)
}

/**
 * Deletes a trashed document for good — the trash screen's "Delete permanently", after the
 * user confirms. See `DocumentRepository.deletePermanently` for exactly what this removes.
 */
class DeleteDocumentPermanentlyUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val chatImages: DocumentChatImages,
) {
    suspend operator fun invoke(documentId: String): PamResult<Unit> {
        // The conversations go with the document (the database cascades), so their ids are read first.
        val conversationIds = chatImages.conversationIdsOf(listOf(documentId))
        val result = documentRepository.deletePermanently(documentId)
        if (result is PamResult.Success) chatImages.deleteAll(conversationIds)
        return result
    }
}

/**
 * The pictures attached to the chats of documents that are being deleted for good. A conversation that disappears with its
 * document (not through [com.postsaimanager.core.domain.usecase.StartNewChatUseCase]) must take its `chat-attachments/<conversation>`
 * folder along: this is the one place that does it.
 */
class DocumentChatImages @Inject constructor(
    private val conversations: ConversationRepository,
    private val images: ChatImageStore,
) {
    suspend fun conversationIdsOf(documentIds: List<String>): List<String> =
        documentIds.flatMap { id -> conversations.getConversationsForDocument(id).first().map { it.id } }

    suspend fun deleteAll(conversationIds: List<String>) {
        conversationIds.forEach { images.deleteAll(it) }
    }
}

/**
 * Permanently deletes every document that has been in the trash longer than [retentionMs]
 * (30 days). Run once on app start, in the main process only — see `PostsAiManagerApp`.
 */
class PurgeExpiredDocumentsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val chatImages: DocumentChatImages,
) {
    suspend operator fun invoke(retentionMs: Long = DEFAULT_RETENTION_MS): PamResult<Int> {
        val cutoff = System.currentTimeMillis() - retentionMs
        val expired = documentRepository.observeTrash().first().filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
        val conversationIds = chatImages.conversationIdsOf(expired.map { it.id })
        val result = documentRepository.purgeTrashOlderThan(cutoff)
        if (result is PamResult.Success) chatImages.deleteAll(conversationIds)
        return result
    }

    companion object {
        const val DEFAULT_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
