package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
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
) {
    suspend operator fun invoke(documentId: String): PamResult<Unit> =
        documentRepository.deletePermanently(documentId)
}

/**
 * Permanently deletes every document that has been in the trash longer than [retentionMs]
 * (30 days). Run once on app start, in the main process only — see `PostsAiManagerApp`.
 */
class PurgeExpiredDocumentsUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(retentionMs: Long = DEFAULT_RETENTION_MS): PamResult<Int> =
        documentRepository.purgeTrashOlderThan(System.currentTimeMillis() - retentionMs)

    companion object {
        const val DEFAULT_RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
