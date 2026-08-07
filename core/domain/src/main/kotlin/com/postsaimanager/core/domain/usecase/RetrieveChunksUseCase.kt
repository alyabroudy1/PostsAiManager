package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.VectorMath
import com.postsaimanager.core.domain.repository.DocumentChunkRepository
import com.postsaimanager.core.domain.repository.StoredChunk
import javax.inject.Inject

/** A passage judged relevant to a query, with the reason it ranked. */
data class RetrievedChunk(
    val chunk: StoredChunk,
    val score: Float,
    val matchedSemantically: Boolean,
    val matchedByKeyword: Boolean,
)

/**
 * Finds the passages most relevant to a question, across every document.
 *
 * ### Why hybrid rather than pure vector search
 *
 * The corpus is German business correspondence, and the questions people ask about it are
 * frequently **exact-match** questions: a reference number, an IBAN, an amount, a date.
 * Embeddings are specifically weak there — `BG 1234/5678` and `BG 8765/4321` sit almost on
 * top of each other in vector space, while keyword search separates them perfectly.
 * Conversely "what did they decide about my appeal?" is meaningless to keyword search and
 * exactly what embeddings are for.
 *
 * Neither alone is adequate, so both run and the results are fused.
 *
 * ### Why reciprocal rank fusion
 *
 * Cosine similarity and keyword overlap are on unrelated scales; combining them by weighted
 * sum requires normalising two distributions that shift with corpus and query. RRF uses
 * only the **rank** in each list, so no normalisation is needed and one runaway score
 * cannot dominate. A passage that ranks respectably in both beats one that tops a single
 * list — which is the behaviour wanted.
 *
 * ### Degradation
 *
 * With no embedding model the semantic half is skipped and keyword results are returned
 * alone. Search gets worse, not broken — and callers are told which happened so the UI can
 * say so rather than quietly returning less.
 */
class RetrieveChunksUseCase @Inject constructor(
    private val chunkRepository: DocumentChunkRepository,
    private val embeddingService: EmbeddingService,
) {

    data class Result(
        val chunks: List<RetrievedChunk>,
        /** False when no embedding model was available — the UI should say so. */
        val semanticSearchUsed: Boolean,
    )

    suspend operator fun invoke(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        documentId: String? = null,
    ): Result {
        if (query.isBlank()) return Result(emptyList(), false)

        val corpus = if (documentId != null) {
            chunkRepository.getForDocument(documentId)
        } else {
            chunkRepository.getAll()
        }
        if (corpus.isEmpty()) return Result(emptyList(), false)

        val keywordRanked = rankByKeyword(query, corpus)

        val queryVector = if (embeddingService.isReady) {
            (embeddingService.embed(query) as? PamResult.Success)?.data
        } else {
            null
        }

        if (queryVector == null) {
            return Result(
                chunks = keywordRanked.take(limit).map {
                    RetrievedChunk(it, 0f, matchedSemantically = false, matchedByKeyword = true)
                },
                semanticSearchUsed = false,
            )
        }

        val semanticRanked = corpus
            .mapNotNull { chunk ->
                // Only vectors from the *same* model are comparable; a chunk embedded by a
                // previous model would otherwise contribute a meaningless score.
                if (chunk.embeddingModelId != embeddingService.modelId) return@mapNotNull null
                val vector = chunk.embedding ?: return@mapNotNull null
                chunk to VectorMath.cosineSimilarity(queryVector, vector)
            }
            .filter { it.second >= MIN_SEMANTIC_SIMILARITY }
            .sortedByDescending { it.second }

        val fused = fuse(
            semantic = semanticRanked.map { it.first },
            keyword = keywordRanked,
        )

        val semanticIds = semanticRanked.map { it.first.id }.toSet()
        val keywordIds = keywordRanked.map { it.id }.toSet()

        return Result(
            chunks = fused.take(limit).map { (chunk, score) ->
                RetrievedChunk(
                    chunk = chunk,
                    score = score,
                    matchedSemantically = chunk.id in semanticIds,
                    matchedByKeyword = chunk.id in keywordIds,
                )
            },
            semanticSearchUsed = true,
        )
    }

    /**
     * Term-overlap ranking.
     *
     * Deliberately simple: it exists to catch the exact tokens embeddings miss — reference
     * numbers, IBANs, dates — not to be a search engine. Room FTS would be the upgrade if
     * this proves insufficient.
     */
    private fun rankByKeyword(query: String, corpus: List<StoredChunk>): List<StoredChunk> {
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}/.-]+"))
            .filter { it.length >= MIN_TERM_LENGTH }
            .toSet()
        if (terms.isEmpty()) return emptyList()

        return corpus
            .map { chunk ->
                val haystack = chunk.text.lowercase()
                chunk to terms.count { haystack.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Reciprocal rank fusion: score = Σ 1 / (k + rank). */
    private fun fuse(
        semantic: List<StoredChunk>,
        keyword: List<StoredChunk>,
    ): List<Pair<StoredChunk, Float>> {
        val scores = mutableMapOf<String, Float>()
        val byId = mutableMapOf<String, StoredChunk>()

        fun accumulate(list: List<StoredChunk>) {
            list.forEachIndexed { index, chunk ->
                byId[chunk.id] = chunk
                scores[chunk.id] = (scores[chunk.id] ?: 0f) + 1f / (RRF_K + index + 1)
            }
        }

        accumulate(semantic)
        accumulate(keyword)

        return scores.entries
            .sortedByDescending { it.value }
            .mapNotNull { entry -> byId[entry.key]?.let { it to entry.value } }
    }

    private companion object {
        const val DEFAULT_LIMIT = 6

        /**
         * Standard RRF constant. Large enough that the top few ranks are not wildly more
         * valuable than the next few, which is what lets agreement across lists win.
         */
        const val RRF_K = 60f

        /** Below this, cosine similarity is noise rather than weak relevance. */
        const val MIN_SEMANTIC_SIMILARITY = 0.25f

        /** One- and two-character tokens match everything and rank nothing. */
        const val MIN_TERM_LENGTH = 3
    }
}
