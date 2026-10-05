package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.testing.FakeDocumentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The review-state contract of the document repository, as the fake implements it (the real one follows the same rules). */
class FakeReviewStateTest {

    private val repository = FakeDocumentRepository()

    private fun field(id: String, state: ReviewState) = ExtractedData(
        id = id, documentId = "d", fieldName = id, fieldValue = "v", fieldType = ExtractedFieldType.TEXT,
        confidence = 0.9f, reviewState = state,
    )

    @Test
    fun `confirm all reads the review state, not the old flags`() = runTest {
        // Flags of a plain machine row on purpose: only reviewState says what a person did.
        repository.seedExtracted(
            "d",
            field("open", ReviewState.UNREVIEWED), field("confirmed", ReviewState.CONFIRMED),
            field("edited", ReviewState.EDITED), field("ignored", ReviewState.IGNORED),
        )

        val result = repository.confirmAllExtractedFields("d")

        assertThat((result as PamResult.Success).data.map { it.id }).containsExactly("open")
        val states = repository.observeExtractedData("d").first().associate { it.id to it.reviewState }
        assertThat(states["open"]).isEqualTo(ReviewState.CONFIRMED)
        assertThat(states["edited"]).isEqualTo(ReviewState.EDITED)
        assertThat(states["ignored"]).isEqualTo(ReviewState.IGNORED)
    }

    @Test
    fun `setting a field to EDITED is rejected, an edit goes through updateExtractedField`() = runTest {
        repository.seedExtracted("d", field("open", ReviewState.UNREVIEWED))

        assertThat(repository.setFieldReviewState("open", ReviewState.EDITED)).isInstanceOf(PamResult.Error::class.java)
        assertThat(repository.observeExtractedData("d").first().single().reviewState).isEqualTo(ReviewState.UNREVIEWED)
    }
}
