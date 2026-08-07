package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [BuildChatContextUseCase] — what the model is actually told.
 *
 * The budgeting matters more than it looks: a local model has 2–8 k tokens of context, and
 * a prompt that overflows it pushes out the user's own question. The model would then
 * receive a letter and no request.
 */
class BuildChatContextUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val useCase = BuildChatContextUseCase(documents, profiles)

    private fun field(
        name: String,
        value: String,
        confirmed: Boolean = false,
        confidence: Float = 0.9f,
    ) = ExtractedData(
        id = "f-$name",
        documentId = "d1",
        fieldName = name,
        fieldValue = value,
        fieldType = ExtractedFieldType.TEXT,
        confidence = confidence,
        isConfirmed = confirmed,
    )

    private fun page(text: String) = DocumentPage(
        id = "p1",
        documentId = "d1",
        pageNumber = 1,
        imagePath = "/tmp/p1.jpg",
        ocrText = text,
        width = 0,
        height = 0,
    )

    @Nested
    @DisplayName("Without a document")
    inner class Standalone {

        @Test
        fun `returns a standalone prompt when no document is open`() = runTest {
            val prompt = useCase(documentId = null, contextTokens = 4096)

            assertThat(prompt).contains("No document is open")
            assertThat(prompt).contains("correspondence")
        }

        @Test
        fun `falls back to standalone when the document cannot be found`() = runTest {
            val prompt = useCase(documentId = "missing", contextTokens = 4096)
            assertThat(prompt).contains("No document is open")
        }
    }

    @Nested
    @DisplayName("Grounding")
    inner class Grounding {

        @Test
        fun `includes title, extracted fields and parties`() = runTest {
            documents.seed(testDocument(id = "d1", title = "Bescheid über Leistungen"))
            documents.seedExtracted(
                "d1",
                field("Deadline", "31.01.2026"),
                field("Sender Organization", "Jobcenter Berlin"),
            )
            profiles.seed(testProfile(id = "p1", name = "Jobcenter Berlin"))
            profiles.linkProfileToDocument("p1", "d1", ProfileRole.SENDER)

            val prompt = useCase(documentId = "d1", contextTokens = 4096)

            assertThat(prompt).contains("Bescheid über Leistungen")
            assertThat(prompt).contains("31.01.2026")
            assertThat(prompt).contains("Jobcenter Berlin")
            assertThat(prompt).contains("sender")
        }

        @Test
        @DisplayName("instructs the model not to invent details")
        fun `includes grounding instructions`() = runTest {
            documents.seed(testDocument(id = "d1"))

            val prompt = useCase(documentId = "d1", contextTokens = 4096)

            assertThat(prompt).contains("Do not invent")
            assertThat(prompt).contains("say so plainly")
        }

        @Test
        @DisplayName("marks low-confidence fields so they are not repeated as fact")
        fun `flags uncertain extractions`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedExtracted(
                "d1",
                field("IBAN", "DE89370400440532013000", confirmed = true),
                field("Amount", "563,00 EUR", confidence = 0.3f),
            )

            val prompt = useCase(documentId = "d1", contextTokens = 4096)

            assertThat(prompt).contains("563,00 EUR (uncertain)")
            // A confirmed value carries no hedge — the user vouched for it.
            assertThat(prompt).contains("DE89370400440532013000\n")
        }
    }

    @Nested
    @DisplayName("Context budgeting")
    inner class Budgeting {

        private val hugeOcr = "Sehr geehrte Damen und Herren, ".repeat(2_000) // ~62 k chars

        @Test
        @DisplayName("a huge document is truncated rather than allowed to overflow")
        fun `truncates oversized OCR text`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedPages("d1", page(hugeOcr))

            val prompt = useCase(documentId = "d1", contextTokens = 2048)

            // 2048 tokens minus reply/template reserves, at ~3 chars per token, is a few
            // thousand characters — far below the 62 k of raw OCR.
            assertThat(prompt.length).isLessThan(hugeOcr.length)
            assertThat(prompt).contains("shortened to fit")
        }

        @Test
        @DisplayName("truncation is announced, never silent")
        fun `states that the document was shortened`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedPages("d1", page(hugeOcr))

            val prompt = useCase(documentId = "d1", contextTokens = 2048)

            // The model must be able to say the text was cut rather than answer
            // confidently from a fragment.
            assertThat(prompt).contains("omitted to fit the context window")
        }

        @Test
        fun `a short document is included whole`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedPages("d1", page("Kurzer Brief mit wenig Text."))

            val prompt = useCase(documentId = "d1", contextTokens = 4096)

            assertThat(prompt).contains("Kurzer Brief mit wenig Text.")
            assertThat(prompt).doesNotContain("shortened to fit")
        }

        @Test
        @DisplayName("extracted fields survive even when OCR text does not")
        fun `fields are prioritised over raw text`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedExtracted("d1", field("Deadline", "31.01.2026"))
            documents.seedPages("d1", page(hugeOcr))

            // A tiny window: only the highest-signal content can survive.
            val prompt = useCase(documentId = "d1", contextTokens = 512)

            // The distilled answer is kept; the bulky source is dropped.
            assertThat(prompt).contains("31.01.2026")
        }

        @Test
        fun `an absurdly small window still produces a usable prompt`() = runTest {
            documents.seed(testDocument(id = "d1"))
            documents.seedPages("d1", page(hugeOcr))

            val prompt = useCase(documentId = "d1", contextTokens = 64)

            assertThat(prompt).isNotEmpty()
            assertThat(prompt).contains("Instructions")
        }
    }
}
