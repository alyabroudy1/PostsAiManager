package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChangeDocumentFamilyUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val processor = FakeDocumentProcessor()
    private val useCase = ChangeDocumentFamilyUseCase(documents, ReadAgainAsFamilyUseCase(processor))

    @Test
    fun `changing the type stores it as the person's and reads the document again with it pinned`() = runTest {
        documents.seed(testDocument(id = "d1", extractionType = "official_letter"))

        val result = useCase("d1", "appointment_reminder")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val stored = documents.getDocuments().first().single()
        assertThat(stored.extractionType).isEqualTo("appointment_reminder")
        assertThat(stored.familySource).isEqualTo(FamilySource.USER)
        assertThat(processor.enqueueCalls)
            .containsExactly(FakeDocumentProcessor.EnqueueCall("d1", force = true, forcedFamily = "appointment_reminder"))
    }

    @Test
    fun `an unknown type changes nothing and reads nothing`() = runTest {
        documents.seed(testDocument(id = "d1", extractionType = "official_letter"))

        assertThat(useCase("d1", "astrology")).isInstanceOf(PamResult.Error::class.java)

        assertThat(documents.getDocuments().first().single().extractionType).isEqualTo("official_letter")
        assertThat(processor.enqueueCalls).isEmpty()
    }
}
