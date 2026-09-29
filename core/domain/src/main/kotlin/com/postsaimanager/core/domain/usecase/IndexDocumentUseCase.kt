package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.StoredChunk
import javax.inject.Inject

/**
 * Makes a document searchable: split it, embed the pieces, store them.
 *
 * Runs after extraction, on the same OCR text the extractor saw.
 *
 * ### Indexing never fails a document
 *
 * A document that cannot be embedded is still a document the user scanned. Every failure
 * here degrades instead of propagating:
 *
 * | Situation | Result |
 * |---|---|
 * | No embedding model installed | chunks stored as text — keyword search works |
 * | Embedding fails mid-batch | chunks stored as text — keyword search works |
 * | Storage fails | reported as an error; there is nothing to fall back to |
 *
 * The first two return [PamResult.Success] with `embedded = false` rather than an error,
 * because from the user's side nothing is broken — search is simply less clever. The
 * un-embedded chunks stay visible to [DocumentChunkRepository.documentIdsMissingEmbeddings]
 * so they can be upgraded once a model is installed.
 */
class IndexDocumentUseCase @Inject constructor(
    private val chunkRepository: DocumentChunkRepository,
    private val embeddingService: EmbeddingService,
) {

    data class Result(
        val documentId: String,
        val chunkCount: Int,
        /** False when the chunks were stored without vectors — keyword search only. */
        val embedded: Boolean,
    )

    /** One page's OCR text, keyed by its page number — see the page-aware chunking KDoc. */
    data class PageText(val pageNumber: Int?, val text: String)

    /**
     * Indexes a document page by page (4.0), so each chunk knows which page it came from —
     * a retrieved passage can then be cited as "p.3" instead of an opaque ordinal.
     *
     * Chunking runs **per page** rather than on the pages joined into one string: joining
     * first would let [TextChunker] draw a passage boundary straight across a page break,
     * at which point no single page number describes it. [TextChunk.ordinal] is local to
     * each page's own call, so it is discarded and every chunk is renumbered with a single
     * document-wide counter afterwards — [chunkId] (and therefore re-indexing in place)
     * depends on that ordinal being stable and unique per document, not per page.
     */
    suspend operator fun invoke(documentId: String, pages: List<PageText>): PamResult<Result> {
        val pieces = pages
            .flatMap { page -> TextChunker.chunk(page.text).map { it.copy(pageNumber = page.pageNumber) } }
            .mapIndexed { globalOrdinal, piece -> piece.copy(ordinal = globalOrdinal) }

        if (pieces.isEmpty()) {
            // Still a write: a document re-processed into nothing must not keep serving
            // passages from the text it used to have.
            return runCatching { chunkRepository.replaceChunks(documentId, emptyList()) }
                .fold(
                    onSuccess = { PamResult.Success(Result(documentId, 0, embedded = false)) },
                    onFailure = { storageError(it) },
                )
        }

        val vectors = embedAll(pieces.map { it.text })

        val chunks = pieces.mapIndexed { index, piece ->
            StoredChunk(
                // Deterministic rather than random: re-indexing a document overwrites its
                // chunks in place instead of accumulating a new set of ids each time, and
                // an id stays meaningful in a log.
                id = chunkId(documentId, piece.ordinal),
                documentId = documentId,
                ordinal = piece.ordinal,
                text = piece.text,
                embedding = vectors?.getOrNull(index),
                embeddingModelId = if (vectors != null) embeddingService.modelId else null,
                pageNumber = piece.pageNumber,
            )
        }

        return runCatching { chunkRepository.replaceChunks(documentId, chunks) }
            .fold(
                onSuccess = {
                    PamResult.Success(
                        Result(documentId, chunks.size, embedded = vectors != null),
                    )
                },
                onFailure = { storageError(it) },
            )
    }

    /**
     * Convenience for a single blob of text with no page boundaries — every test and a few
     * callers that never had per-page text predate 4.0. Equivalent to a single [PageText]
     * with no page number, so its chunks come out with `pageNumber = null`, same as a
     * document indexed before page-aware chunking existed.
     */
    suspend operator fun invoke(documentId: String, text: String): PamResult<Result> =
        invoke(documentId, listOf(PageText(pageNumber = null, text = text)))

    /**
     * @return one vector per text, or null if embedding was unavailable or failed — the
     *   caller stores the chunks as text either way.
     */
    private suspend fun embedAll(texts: List<String>): List<FloatArray>? {
        if (!embeddingService.checkReady()) return null

        val vectors = mutableListOf<FloatArray>()
        // Bounded batches. Embedding a long document in one call would hold every chunk's
        // per-token hidden state at once, which is tens of megabytes on a document that is
        // only a few pages — and the throughput gain flattens out well before this size.
        for (batch in texts.chunked(EMBED_BATCH_SIZE)) {
            when (val result = embeddingService.embedAll(batch)) {
                is PamResult.Success -> vectors += result.data
                // Partial vectors are worse than none: chunks would be stored in a mix of
                // embedded and not, and the un-embedded tail would silently never match.
                is PamResult.Error -> return null
            }
        }
        // A model that returns the wrong count would misalign vectors with their text,
        // giving every chunk a neighbour's meaning.
        return vectors.takeIf { it.size == texts.size }
    }

    private fun storageError(cause: Throwable): PamResult.Error =
        PamResult.Error(PamError.DatabaseError(cause))

    companion object {
        /** Stable across re-indexing; see the id comment in [invoke]. */
        fun chunkId(documentId: String, ordinal: Int): String = "$documentId#$ordinal"

        private const val EMBED_BATCH_SIZE = 8
    }
}
