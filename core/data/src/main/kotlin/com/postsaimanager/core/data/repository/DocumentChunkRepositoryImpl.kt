package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.DocumentChunkDao
import com.postsaimanager.core.data.database.entity.DocumentChunkEntity
import com.postsaimanager.core.domain.ai.VectorMath
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.StoredChunk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [DocumentChunkRepository].
 *
 * Embeddings cross the boundary as `FloatArray` and are stored as little-endian float32
 * BLOBs. A BLOB that is not a whole number of floats decodes to null rather than to a
 * shorter vector — a truncated vector would still produce plausible-looking similarity
 * scores that mean nothing.
 */
@Singleton
class DocumentChunkRepositoryImpl @Inject constructor(
    private val chunkDao: DocumentChunkDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DocumentChunkRepository {

    override suspend fun getAllEmbedded(): List<StoredChunk> = withContext(ioDispatcher) {
        chunkDao.getAllEmbedded().map(::toDomain)
    }

    override suspend fun getForDocument(documentId: String): List<StoredChunk> =
        withContext(ioDispatcher) { chunkDao.getForDocument(documentId).map(::toDomain) }

    override suspend fun replaceChunks(documentId: String, chunks: List<StoredChunk>) =
        withContext(ioDispatcher) {
            // Delete-then-insert: re-indexing a shortened document would otherwise leave
            // orphaned passages that keep matching text no longer present.
            chunkDao.deleteForDocument(documentId)
            chunkDao.insertAll(chunks.map(::toEntity))
        }

    override suspend fun unindexedDocumentIds(): List<String> =
        withContext(ioDispatcher) { chunkDao.getUnindexedDocumentIds() }

    override suspend fun documentIdsNeedingReindex(currentModelId: String): List<String> =
        withContext(ioDispatcher) {
            chunkDao.getDocumentIdsEmbeddedByOtherModel(currentModelId)
        }

    override suspend fun deleteForDocument(documentId: String) =
        withContext(ioDispatcher) { chunkDao.deleteForDocument(documentId) }

    override suspend fun embeddedChunkCount(): Int =
        withContext(ioDispatcher) { chunkDao.embeddedChunkCount() }

    private fun toDomain(entity: DocumentChunkEntity) = StoredChunk(
        id = entity.id,
        documentId = entity.documentId,
        ordinal = entity.ordinal,
        text = entity.text,
        embedding = VectorMath.fromBytes(entity.embedding),
        embeddingModelId = entity.embeddingModelId,
    )

    private fun toEntity(chunk: StoredChunk) = DocumentChunkEntity(
        id = chunk.id,
        documentId = chunk.documentId,
        ordinal = chunk.ordinal,
        text = chunk.text,
        embedding = chunk.embedding?.let(VectorMath::toBytes),
        embeddingModelId = chunk.embeddingModelId,
        createdAt = System.currentTimeMillis(),
    )
}
