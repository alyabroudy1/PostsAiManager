package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.ExtractorVersion
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.TimelineCodes
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import com.postsaimanager.core.model.UserPreferences
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeDocumentProcessor
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeTimelineRepository
import com.postsaimanager.core.testing.FakeUserPreferencesRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ReprocessOutdatedDocumentsUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val timeline = FakeTimelineRepository()
    private val preferences = FakeUserPreferencesRepository()
    private val model = FakeActiveModelProvider()
    private val processor = FakeDocumentProcessor()

    private val reprocess = ReprocessOutdatedDocumentsUseCase(documents, timeline, preferences, model, processor)

    private fun extracted(id: String, version: String?, createdAt: Long = 0L, deletedAt: Long? = null) =
        testDocument(
            id = id,
            status = DocumentStatus.EXTRACTED,
            extractorVersion = version,
            createdAt = createdAt,
            deletedAt = deletedAt,
        )

    private fun failedReprocess(documentId: String, id: String) = TimelineEvent(
        id = id,
        documentId = documentId,
        eventType = TimelineEventType.ENTITIES_EXTRACTED,
        title = "Update skipped",
        createdAt = 0L,
        code = TimelineCodes.REPROCESS_FAILED,
        args = listOf("error"),
    )

    @Test
    @DisplayName("schedules letters with an older or missing version, not current ones")
    fun selectsOlderAndNull() = runTest {
        documents.seed(
            extracted("old", "entity-extractor-1"),
            extracted("none", null),
            extracted("fresh", ExtractorVersion.CURRENT),
        )

        val scheduled = reprocess()

        assertThat(scheduled).isEqualTo(2)
        assertThat(processor.reprocessCalls).containsExactly("old", "none")
    }

    @Test
    @DisplayName("with Gemma alone installed (no llama reader) older letters are still scheduled: the Gemma reader runs on the chat model")
    fun readsWithGemmaAlone() = runTest {
        documents.seed(extracted("old", "entity-extractor-1"))
        model.llamaReaderInstalled = false

        assertThat(reprocess()).isEqualTo(1)
        assertThat(processor.reprocessCalls).containsExactly("old")
    }

    @Test
    @DisplayName("leaves trashed letters and letters that are not finished to the ordinary pipeline")
    fun excludesTrashedAndUnfinished() = runTest {
        documents.seed(
            extracted("trashed", null, deletedAt = 5L),
            testDocument(id = "queued", status = DocumentStatus.QUEUED),
            testDocument(id = "failed", status = DocumentStatus.FAILED),
            testDocument(id = "new", status = DocumentStatus.NEW),
            extracted("ok", null),
        )

        reprocess()

        assertThat(processor.reprocessCalls).containsExactly("ok")
    }

    @Test
    @DisplayName("schedules at most the limit per start, newest first, and the next start continues")
    fun limitsPerStart() = runTest {
        (1..8).forEach { documents.seed(extracted("d$it", null, createdAt = it.toLong())) }

        assertThat(reprocess(limit = 5)).isEqualTo(5)
        assertThat(processor.reprocessCalls).containsExactly("d8", "d7", "d6", "d5", "d4").inOrder()

        // Those five now carry the current version; the next start takes the rest.
        documents.clear()
        (1..3).forEach { documents.seed(extracted("d$it", null, createdAt = it.toLong())) }
        (4..8).forEach { documents.seed(extracted("d$it", ExtractorVersion.CURRENT, createdAt = it.toLong())) }
        processor.reprocessCalls.clear()
        assertThat(reprocess(limit = 5)).isEqualTo(3)
        assertThat(processor.reprocessCalls).containsExactly("d3", "d2", "d1").inOrder()
    }

    @Test
    @DisplayName("does nothing when the setting is off")
    fun respectsSetting() = runTest {
        documents.seed(extracted("a", null))
        preferences.setUpdateOlderLettersAutomatically(false)

        assertThat(reprocess()).isEqualTo(0)
        assertThat(processor.reprocessCalls).isEmpty()
    }

    @Test
    @DisplayName("the setting is on by default")
    fun onByDefault() {
        assertThat(UserPreferences().updateOlderLettersAutomatically).isTrue()
    }

    @Test
    @DisplayName("does nothing without a model, since a re-read could not improve anything")
    fun needsModel() = runTest {
        documents.seed(extracted("a", null))
        model.path = null

        assertThat(reprocess()).isEqualTo(0)
    }

    @Test
    @DisplayName("a failed letter is retried once on a later start, then left alone")
    fun retriesOnce() = runTest {
        documents.seed(extracted("a", null), extracted("b", null))
        timeline.seed(failedReprocess("a", "e1"))

        reprocess()
        assertThat(processor.reprocessCalls).containsExactly("a", "b")

        processor.reprocessCalls.clear()
        timeline.seed(failedReprocess("a", "e2"))

        reprocess()
        assertThat(processor.reprocessCalls).containsExactly("b")
    }

    @Test
    @DisplayName("a letter that failed and is skipped does not use up a slot")
    fun skippedLettersDoNotConsumeTheLimit() = runTest {
        documents.seed(extracted("a", null, createdAt = 3), extracted("b", null, createdAt = 2))
        timeline.seed(failedReprocess("a", "e1"), failedReprocess("a", "e2"))

        assertThat(reprocess(limit = 1)).isEqualTo(1)
        assertThat(processor.reprocessCalls).containsExactly("b")
    }
}
