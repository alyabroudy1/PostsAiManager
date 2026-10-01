package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.testing.FakeDocumentProcessor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ReadAgainAsFamilyUseCaseTest {

    private val processor = FakeDocumentProcessor()
    private val useCase = ReadAgainAsFamilyUseCase(processor)

    @Test
    fun `a known family is handed to the processor with the document`() = runTest {
        val result = useCase("d1", "invoice_bill")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(processor.readAsCalls).containsExactly("d1" to "invoice_bill")
    }

    @Test
    fun `an unknown family is rejected and nothing is scheduled`() = runTest {
        val result = useCase("d1", "astrology")

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(processor.readAsCalls).isEmpty()
        assertThat(processor.enqueueCalls).isEmpty()
    }

    @Test
    fun `a processor that cannot force a family still reads the document again`() = runTest {
        val enqueued = mutableListOf<Pair<String, Boolean>>()
        // Only the port's required members: enqueueReadAs is the interface's default.
        val plain = object : DocumentProcessor {
            override val processingState = kotlinx.coroutines.flow.MutableStateFlow<com.postsaimanager.core.model.ProcessingState>(
                com.postsaimanager.core.model.ProcessingState.Idle,
            )

            override suspend fun processDocument(documentId: String, reprocess: Boolean) = processor.processDocument(documentId, reprocess)
            override suspend fun enqueueReprocess(documentId: String) = Unit
            override suspend fun enqueue(documentId: String, force: Boolean) {
                enqueued += documentId to force
            }

            override fun cancel(documentId: String) = Unit
        }

        plain.enqueueReadAs("d1", "receipt")

        assertThat(enqueued).containsExactly("d1" to true)
    }
}
