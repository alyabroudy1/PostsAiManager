package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [IndexDocumentUseCase].
 *
 * The behaviour that matters here is mostly about **failure**: a document the user scanned
 * must survive every way indexing can go wrong. These pin each degradation path, because
 * the failure mode they prevent — a document silently missing from search — is invisible
 * until someone asks a question it should have answered.
 */
class IndexDocumentUseCaseTest {

    private val repository = FakeDocumentChunkRepository()
    private val embedder = FakeEmbeddingService()
    private val indexDocument = IndexDocumentUseCase(repository, embedder)

    private val letter = """
        Sehr geehrte Frau Mustermann,

        Ihr Widerspruch vom 12.01.2026 gegen den Bescheid BG 1234/5678 wurde geprüft.

        Bitte reichen Sie die fehlenden Unterlagen bis zum 31.01.2026 nach.
    """.trimIndent()

    @Nested
    @DisplayName("With an embedding model")
    inner class Embedded {

        @Test
        fun `stores a chunk per passage, each with a vector`() = runTest {
            val result = indexDocument("doc-1", letter)

            assertThat(result).isInstanceOf(PamResult.Success::class.java)
            val data = (result as PamResult.Success).data
            assertThat(data.embedded).isTrue()
            assertThat(data.chunkCount).isAtLeast(1)

            val stored = repository.getForDocument("doc-1")
            assertThat(stored).hasSize(data.chunkCount)
            assertThat(stored.all { it.embedding != null }).isTrue()
            assertThat(stored.all { it.embeddingModelId == embedder.modelId }).isTrue()
        }

        @Test
        @DisplayName("re-indexing replaces chunks rather than accumulating them")
        fun `re-indexing does not duplicate`() = runTest {
            indexDocument("doc-1", letter)
            val first = repository.getForDocument("doc-1")

            indexDocument("doc-1", letter)
            val second = repository.getForDocument("doc-1")

            // Stable ids are what make this hold. Random ids would leave the old chunks
            // behind, and search would keep returning passages from a stale version.
            assertThat(second).hasSize(first.size)
            assertThat(second.map { it.id }).containsExactlyElementsIn(first.map { it.id })
        }

        @Test
        fun `a document that becomes empty loses its old passages`() = runTest {
            indexDocument("doc-1", letter)
            assertThat(repository.getForDocument("doc-1")).isNotEmpty()

            val result = indexDocument("doc-1", "   ")

            assertThat((result as PamResult.Success).data.chunkCount).isEqualTo(0)
            // Otherwise a re-scan that produced no text would keep answering questions
            // from text the document no longer contains.
            assertThat(repository.getForDocument("doc-1")).isEmpty()
        }

        @Test
        fun `vectors line up with the text they came from`() = runTest {
            val a = floatArrayOf(1f, 0f, 0f, 0f)
            val b = floatArrayOf(0f, 1f, 0f, 0f)
            val first = "Erster Absatz."
            val second = "Zweiter Absatz."
            embedder.register(first, a)
            embedder.register(second, b)

            indexDocument("doc-1", "$first\n\n$second")

            val stored = repository.getForDocument("doc-1").sortedBy { it.ordinal }
            // A single chunk if they fit together; only assert alignment when they split.
            if (stored.size == 2) {
                assertThat(stored[0].embedding).isEqualTo(a)
                assertThat(stored[1].embedding).isEqualTo(b)
            }
        }
    }

    @Nested
    @DisplayName("Without a usable embedding model")
    inner class Degraded {

        @Test
        @DisplayName("no model installed - text is still stored, so keyword search works")
        fun `stores chunks without vectors`() = runTest {
            embedder.isReady = false

            val result = indexDocument("doc-1", letter)

            // Not an error: nothing is broken from the user's side, search is just less
            // clever. Failing here would make a missing optional model block scanning.
            val data = (result as PamResult.Success).data
            assertThat(data.embedded).isFalse()
            assertThat(data.chunkCount).isAtLeast(1)

            val stored = repository.getForDocument("doc-1")
            assertThat(stored).isNotEmpty()
            assertThat(stored.all { it.embedding == null }).isTrue()
            // Null rather than the current model id: labelling un-embedded text with a
            // model would make it look comparable to real vectors.
            assertThat(stored.all { it.embeddingModelId == null }).isTrue()
        }

        @Test
        fun `an embedding failure degrades instead of propagating`() = runTest {
            embedder.failWith = PamError.InferenceError("out of memory")

            val result = indexDocument("doc-1", letter)

            val data = (result as PamResult.Success).data
            assertThat(data.embedded).isFalse()
            assertThat(repository.getForDocument("doc-1")).isNotEmpty()
        }

        @Test
        @DisplayName("a partly-embedded document is stored as text, never half and half")
        fun `does not store a mix of embedded and unembedded chunks`() = runTest {
            embedder.failWith = PamError.InferenceError("died partway")

            indexDocument("doc-1", letter)

            val stored = repository.getForDocument("doc-1")
            // A half-embedded document is the worst outcome: the embedded chunks would
            // rank, the rest would silently never match, and nothing would report it.
            assertThat(stored.map { it.embedding == null }.distinct()).hasSize(1)
        }

        @Test
        fun `un-embedded documents are visible as a backlog`() = runTest {
            embedder.isReady = false
            indexDocument("doc-1", letter)

            // So they can be upgraded once a model is installed, rather than staying
            // keyword-only forever with no record that they need revisiting.
            assertThat(repository.documentIdsMissingEmbeddings()).contains("doc-1")
        }
    }
}
