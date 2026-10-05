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
        assertThat(processor.enqueueCalls)
            .containsExactly(FakeDocumentProcessor.EnqueueCall("d1", force = true, forcedFamily = "invoice_bill"))
    }

    @Test
    fun `an unknown family is rejected and nothing is scheduled`() = runTest {
        val result = useCase("d1", "astrology")

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(processor.enqueueCalls).isEmpty()
    }
}
