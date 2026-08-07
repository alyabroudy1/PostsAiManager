package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.testChunk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [RetrieveChunksUseCase] — hybrid retrieval and its fusion.
 *
 * Embeddings are supplied by a fake with test-assigned vectors, so these assert **ranking
 * behaviour** rather than embedding quality. A real model would make the tests measure
 * something they cannot control.
 */
class RetrieveChunksUseCaseTest {

    private val repo = FakeDocumentChunkRepository()
    private val embedder = FakeEmbeddingService()
    private val useCase = RetrieveChunksUseCase(repo, embedder)

    // Orthogonal-ish vectors so "related" and "unrelated" are unambiguous.
    private val appealTopic = floatArrayOf(1f, 0f, 0f, 0f)
    private val unrelated = floatArrayOf(0f, 1f, 0f, 0f)

    @Nested
    @DisplayName("Semantic retrieval")
    inner class Semantic {

        @Test
        fun `finds a passage by meaning rather than wording`() = runTest {
            repo.seed(
                testChunk("c1", text = "Ihr Widerspruch wurde geprüft und abgelehnt.", embedding = appealTopic),
                testChunk("c2", documentId = "d2", text = "Rechnung über Telefongebühren.", embedding = unrelated),
            )
            embedder.register("What happened with my appeal?", appealTopic)

            val result = useCase("What happened with my appeal?")

            assertThat(result.semanticSearchUsed).isTrue()
            assertThat(result.chunks.first().chunk.id).isEqualTo("c1")
            assertThat(result.chunks.first().matchedSemantically).isTrue()
        }

        @Test
        @DisplayName("chunks embedded by a different model are excluded, not mis-scored")
        fun `ignores vectors from another model`() = runTest {
            repo.seed(
                testChunk(
                    "old", text = "Widerspruch abgelehnt.",
                    embedding = appealTopic, embeddingModelId = "previous-model",
                ),
            )
            embedder.register("appeal", appealTopic)

            val result = useCase("appeal")

            // Vectors from different models occupy different spaces; scoring across them
            // produces confident nonsense.
            assertThat(result.chunks.none { it.matchedSemantically }).isTrue()
        }

        @Test
        fun `weak similarity is treated as noise`() = runTest {
            repo.seed(testChunk("c1", text = "Völlig anderes Thema.", embedding = unrelated))
            embedder.register("appeal", appealTopic)

            val result = useCase("appeal")

            // Orthogonal vectors score 0, below the relevance floor.
            assertThat(result.chunks.none { it.matchedSemantically }).isTrue()
        }
    }

    @Nested
    @DisplayName("Keyword retrieval — what embeddings are bad at")
    inner class Keyword {

        @Test
        @DisplayName("an exact reference number is found")
        fun `finds exact identifiers`() = runTest {
            repo.seed(
                testChunk("c1", text = "Aktenzeichen: BG 1234/5678 vom Januar.", embedding = unrelated),
                testChunk("c2", documentId = "d2", text = "Aktenzeichen: BG 8765/4321.", embedding = unrelated),
            )

            val result = useCase("BG 1234/5678")

            // Two reference numbers are near-identical in vector space; only keyword
            // matching separates them.
            assertThat(result.chunks.first().chunk.id).isEqualTo("c1")
            assertThat(result.chunks.first().matchedByKeyword).isTrue()
        }

        @Test
        fun `finds an IBAN`() = runTest {
            repo.seed(
                testChunk("c1", text = "IBAN: DE89370400440532013000", embedding = unrelated),
                testChunk("c2", documentId = "d2", text = "Kein Konto genannt.", embedding = unrelated),
            )

            val result = useCase("DE89370400440532013000")

            assertThat(result.chunks.first().chunk.id).isEqualTo("c1")
        }

        @Test
        fun `very short terms are ignored`() = runTest {
            repo.seed(testChunk("c1", text = "Ein Brief über etwas.", embedding = unrelated))

            // "an" and "of" would match nearly everything and rank nothing.
            val result = useCase("an of")

            assertThat(result.chunks).isEmpty()
        }
    }

    @Nested
    @DisplayName("Fusion")
    inner class Fusion {

        @Test
        @DisplayName("a chunk matching both ways outranks one matching only a single way")
        fun `agreement across both lists wins`() = runTest {
            repo.seed(
                // Matches semantically AND contains the term.
                testChunk("both", text = "Der Widerspruch gegen den Bescheid wurde abgelehnt.", embedding = appealTopic),
                // Semantic only.
                testChunk("semantic", documentId = "d2", text = "Die Entscheidung steht fest.", embedding = appealTopic),
                // Keyword only.
                testChunk("keyword", documentId = "d3", text = "Widerspruch Formular anbei.", embedding = unrelated),
            )
            embedder.register("Widerspruch abgelehnt", appealTopic)

            val result = useCase("Widerspruch abgelehnt")

            assertThat(result.chunks.first().chunk.id).isEqualTo("both")
            assertThat(result.chunks.first().matchedSemantically).isTrue()
            assertThat(result.chunks.first().matchedByKeyword).isTrue()
        }

        @Test
        fun `respects the result limit`() = runTest {
            repeat(20) { i ->
                repo.seed(testChunk("c$i", documentId = "d$i", text = "Widerspruch Nummer $i", embedding = appealTopic))
            }
            embedder.register("Widerspruch", appealTopic)

            assertThat(useCase("Widerspruch", limit = 3).chunks).hasSize(3)
        }

        @Test
        fun `can be scoped to a single document`() = runTest {
            repo.seed(
                testChunk("a", documentId = "d1", text = "Widerspruch im Dokument eins.", embedding = appealTopic),
                testChunk("b", documentId = "d2", text = "Widerspruch im Dokument zwei.", embedding = appealTopic),
            )
            embedder.register("Widerspruch", appealTopic)

            val result = useCase("Widerspruch", documentId = "d2")

            assertThat(result.chunks.map { it.chunk.documentId }.distinct()).containsExactly("d2")
        }
    }

    @Nested
    @DisplayName("Degradation")
    inner class Degradation {

        @Test
        @DisplayName("with no embedding model, keyword search still works — and says so")
        fun `degrades to keyword only`() = runTest {
            embedder.isReady = false
            repo.seed(testChunk("c1", text = "Aktenzeichen BG 1234/5678", embedding = appealTopic))

            val result = useCase("BG 1234/5678")

            // Announced, not silent: the UI can tell the user search is reduced.
            assertThat(result.semanticSearchUsed).isFalse()
            assertThat(result.chunks).isNotEmpty()
            assertThat(result.chunks.first().matchedByKeyword).isTrue()
        }

        @Test
        fun `an embedding failure falls back rather than throwing`() = runTest {
            embedder.failWith = PamError.InferenceError("model unavailable")
            repo.seed(testChunk("c1", text = "Aktenzeichen BG 1234/5678", embedding = appealTopic))

            val result = useCase("BG 1234/5678")

            assertThat(result.semanticSearchUsed).isFalse()
            assertThat(result.chunks).isNotEmpty()
        }

        @Test
        fun `an empty corpus returns nothing`() = runTest {
            assertThat(useCase("anything").chunks).isEmpty()
        }

        @Test
        fun `a blank query returns nothing`() = runTest {
            repo.seed(testChunk("c1", text = "Etwas Text", embedding = appealTopic))
            assertThat(useCase("   ").chunks).isEmpty()
        }
    }
}
