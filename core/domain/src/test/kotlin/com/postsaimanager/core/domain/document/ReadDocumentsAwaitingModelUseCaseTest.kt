package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeTimelineRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Letters scanned while the model was still downloading are read properly as soon as a model is installed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadDocumentsAwaitingModelUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val timeline = FakeTimelineRepository()
    private val model = FakeActiveModelProvider()
    private val processor = FakeDocumentProcessor()
    private val installedModels = MutableStateFlow<List<InstalledModelSummary>>(emptyList())
    private val installedRepository = object : InstalledModelsRepository {
        override val installed: Flow<List<InstalledModelSummary>> = installedModels
        override val activeModelId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setActive(modelId: String) = Unit
    }

    private val useCase = ReadDocumentsAwaitingModelUseCase(documents, timeline, installedRepository, model, processor)

    private fun extracted(id: String, version: String?, createdAt: Long = 0L) =
        testDocument(id = id, status = DocumentStatus.EXTRACTED, extractorVersion = version, createdAt = createdAt)

    private val aModel = InstalledModelSummary("m", "Model", "/m.gguf", 1L, "Q4_K_M", 4096)

    @Test
    fun `letters read without the model are scheduled urgently once a model exists`() = runTest {
        documents.seed(
            extracted("found", ExtractorVersion.FOUND_VALUES),
            extracted("read", ExtractorVersion.CURRENT),
            extracted("older", "extraction-v2-1"),
        )

        assertThat(useCase()).isEqualTo(1)
        assertThat(processor.urgentReprocessCalls).containsExactly("found")
    }

    @Test
    fun `nothing is scheduled while there is no model to read with`() = runTest {
        documents.seed(extracted("found", ExtractorVersion.FOUND_VALUES))
        model.path = null

        assertThat(useCase()).isEqualTo(0)
        assertThat(processor.reprocessCalls).isEmpty()
    }

    @Test
    fun `watching schedules the waiting letters when the model gets installed, not before`() = runTest {
        documents.seed(extracted("found", ExtractorVersion.FOUND_VALUES))
        val job = launch(UnconfinedTestDispatcher(testScheduler), CoroutineStart.UNDISPATCHED) { useCase.watch() }

        assertThat(processor.urgentReprocessCalls).isEmpty()

        installedModels.value = listOf(aModel)
        testScheduler.advanceUntilIdle()

        assertThat(processor.urgentReprocessCalls).containsExactly("found")
        job.cancel()
    }

    @Test
    fun `a letter whose re-read failed twice is left alone`() = runTest {
        documents.seed(extracted("found", ExtractorVersion.FOUND_VALUES))
        repeat(2) { timeline.seed(failedReprocess("found", "e$it")) }

        assertThat(useCase()).isEqualTo(0)
    }

    @Test
    fun `the stamp of a run without the model awaits one, the others do not`() {
        assertThat(ExtractorVersion.awaitsModel(ExtractorVersion.FOUND_VALUES)).isTrue()
        assertThat(ExtractorVersion.awaitsModel(ExtractorVersion.CURRENT)).isFalse()
        assertThat(ExtractorVersion.awaitsModel(null)).isFalse()
    }

    private fun failedReprocess(documentId: String, id: String) = com.postsaimanager.core.model.TimelineEvent(
        id = id,
        documentId = documentId,
        eventType = com.postsaimanager.core.model.TimelineEventType.ENTITIES_EXTRACTED,
        title = "Update skipped",
        createdAt = 0L,
        code = com.postsaimanager.core.model.TimelineCodes.REPROCESS_FAILED,
        args = listOf("error"),
    )
}
