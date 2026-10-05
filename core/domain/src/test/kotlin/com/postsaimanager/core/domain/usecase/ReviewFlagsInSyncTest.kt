package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.testing.FakeDocumentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * `reviewState` owns review state; `isConfirmed` and `deletedByUser` are stored flags that must follow it. They are plain constructor
 * properties, so `copy(isConfirmed = ...)` can desync them; these tests hold every writer to setting all three together.
 */
class ReviewFlagsInSyncTest {

    private val merge = MergeExtractionUseCase()
    private val newId: (String) -> String = { "rev-$it" }

    private fun row(state: ReviewState = ReviewState.UNREVIEWED, value: String = "v") = ExtractedData(
        id = "row", documentId = "d", fieldName = "Amount", fieldValue = value, fieldType = ExtractedFieldType.TEXT,
        confidence = 0.9f, slotKey = "total", machineValue = value, machineConfidence = 0.9f,
        isConfirmed = state == ReviewState.CONFIRMED || state == ReviewState.EDITED,
        deletedByUser = state == ReviewState.IGNORED,
        source = if (state == ReviewState.EDITED) ValueSource.USER else ValueSource.MACHINE,
        reviewState = state,
    )

    @Test
    fun `the check catches a copy that changes a flag without the review state`() {
        assertThat(row().flagsMatchReviewState).isTrue()
        assertThat(row().copy(isConfirmed = true).flagsMatchReviewState).isFalse()
        assertThat(row().copy(deletedByUser = true).flagsMatchReviewState).isFalse()
        assertThat(row(ReviewState.CONFIRMED).copy(isConfirmed = false).flagsMatchReviewState).isFalse()
        assertThat(row(ReviewState.IGNORED).copy(deletedByUser = false).flagsMatchReviewState).isFalse()
    }

    @Test
    fun `the default review state derived from flags agrees with them`() {
        for (confirmed in listOf(false, true)) for (deleted in listOf(false, true)) for (source in ValueSource.entries) {
            val data = ExtractedData(
                id = "r", documentId = "d", fieldName = "n", fieldValue = "v", fieldType = ExtractedFieldType.TEXT, confidence = 1f,
                isConfirmed = confirmed, deletedByUser = deleted, source = source,
            )
            // A person's value that was never confirmed derives EDITED, so a bare USER row with isConfirmed false is the one gap: it is
            // an edit, which the writers always confirm.
            if (source == ValueSource.USER && !confirmed && !deleted) continue
            assertThat(data.flagsMatchReviewState).isTrue()
        }
    }

    @Test
    fun `every use-case writer leaves the flags in step with the review state`() {
        for (start in ReviewState.entries) {
            val outputs = buildList {
                add(merge.applyUserEdit(row(start), "same", 1L, newId).first)
                add(merge.applyUserEdit(row(start), "other", 1L, newId).first)
                add(merge.applyUserDelete(row(start), 1L))
                for (target in ReviewState.entries) add(merge.applyReviewState(row(start), target, 1L))
                add(merge.acceptMachineValue(row(start).copy(machineValue = "m"), 1L, newId).first)
            }
            for (out in outputs) assertThat(out.flagsMatchReviewState).isTrue()
        }
    }

    @Test
    fun `a merge keeps the flags in step for every stored state`() {
        for (state in ReviewState.entries) {
            val fresh = row().copy(id = "fresh", fieldValue = "new")
            val out = merge(listOf(row(state)), listOf(fresh), engineVersion = "v2", now = 5L, newId = newId)
            for (persisted in out.toPersist) assertThat(persisted.flagsMatchReviewState).isTrue()
        }
    }

    @Test
    fun `the fake repository keeps the flags in step too, so the tests built on it stay honest`() = runTest {
        val repo = FakeDocumentRepository()
        repo.seedExtracted("d", row().copy(id = "a"), row().copy(id = "b", fieldName = "Date"), row().copy(id = "c", fieldName = "Other"))

        repo.confirmExtractedField("a")
        repo.setFieldReviewState("b", ReviewState.IGNORED)
        repo.confirmAllExtractedFields("d", onlyConfident = false)
        assertThat(repo.observeExtractedData("d").first().all { it.flagsMatchReviewState }).isTrue()

        for (state in listOf(ReviewState.UNREVIEWED, ReviewState.CONFIRMED, ReviewState.IGNORED)) {
            repo.setFieldReviewState("c", state)
            assertThat(repo.observeExtractedData("d").first().all { it.flagsMatchReviewState }).isTrue()
        }
        repo.updateExtractedField("c", "Other", "typed")
        repo.deleteExtractedField("a")
        assertThat(repo.observeExtractedData("d").first().all { it.flagsMatchReviewState }).isTrue()
    }
}
