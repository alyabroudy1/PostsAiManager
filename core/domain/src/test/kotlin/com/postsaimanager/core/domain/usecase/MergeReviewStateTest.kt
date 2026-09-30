package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.FieldAlternative
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.ValueSource
import org.junit.jupiter.api.Test

/** [MergeExtractionUseCase] protection driven by `reviewState`, the single owner of review state. */
class MergeReviewStateTest {

    private val merge = MergeExtractionUseCase()
    private val newId: (String) -> String = { "rev-$it" }

    private fun stored(state: ReviewState, value: String = "old") = ExtractedData(
        id = "row", documentId = "d", fieldName = "Amount", fieldValue = value,
        fieldType = ExtractedFieldType.TEXT, confidence = 0.9f, slotKey = "total",
        machineValue = value, reviewState = state,
        // Deliberately the flags of a plain machine row: only reviewState says it was reviewed.
        isConfirmed = false, deletedByUser = false, source = ValueSource.MACHINE,
    )

    private fun fresh(value: String = "new", alternatives: List<FieldAlternative> = emptyList()) = ExtractedData(
        id = "fresh", documentId = "d", fieldName = "Amount", fieldValue = value,
        fieldType = ExtractedFieldType.TEXT, confidence = 0.8f, slotKey = "total", alternatives = alternatives,
    )

    private fun run(existing: ExtractedData, fresh: ExtractedData?) =
        merge(listOf(existing), listOfNotNull(fresh), engineVersion = "v2", now = 5L, newId = newId)

    @Test
    fun `a confirmed row keeps its value and flags a differing reading`() {
        val out = run(stored(ReviewState.CONFIRMED), fresh("new"))

        val row = out.toPersist.single()
        assertThat(row.fieldValue).isEqualTo("old")
        assertThat(row.reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(row.machineValue).isEqualTo("new")
        assertThat(row.hasUnreviewedMachineChange).isTrue()
        assertThat(out.idsToDelete).isEmpty()
    }

    @Test
    fun `an edited row keeps its value and flags a differing reading`() {
        val out = run(stored(ReviewState.EDITED), fresh("new"))

        val row = out.toPersist.single()
        assertThat(row.fieldValue).isEqualTo("old")
        assertThat(row.reviewState).isEqualTo(ReviewState.EDITED)
        assertThat(row.hasUnreviewedMachineChange).isTrue()
    }

    @Test
    fun `a confirmed or edited row the extractor no longer finds is kept`() {
        for (state in listOf(ReviewState.CONFIRMED, ReviewState.EDITED)) {
            val out = run(stored(state), null)

            assertThat(out.toPersist.single().fieldValue).isEqualTo("old")
            assertThat(out.idsToDelete).isEmpty()
        }
    }

    @Test
    fun `an ignored row is a tombstone that a fresh reading never resurrects`() {
        val out = run(stored(ReviewState.IGNORED), fresh("new"))

        val row = out.toPersist.single()
        assertThat(row.reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(row.fieldValue).isEqualTo("old")
        assertThat(row.machineValue).isEqualTo("new")
        assertThat(row.hasUnreviewedMachineChange).isFalse()
        assertThat(out.idsToDelete).isEmpty()
    }

    @Test
    fun `an ignored row the extractor no longer finds is kept as a tombstone, not deleted`() {
        val out = run(stored(ReviewState.IGNORED), null)

        assertThat(out.toPersist.single().reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(out.idsToDelete).isEmpty()
    }

    @Test
    fun `an unreviewed row is replaced and takes the fresh alternatives`() {
        val alt = FieldAlternative(value = "other", score = 0.3f, page = 1)

        val out = run(stored(ReviewState.UNREVIEWED), fresh("new", listOf(alt)))

        val row = out.toPersist.single()
        assertThat(row.fieldValue).isEqualTo("new")
        assertThat(row.reviewState).isEqualTo(ReviewState.UNREVIEWED)
        assertThat(row.alternatives).containsExactly(alt)
    }

    @Test
    fun `a reviewed row still takes the fresh alternatives for the edit chips`() {
        val alt = FieldAlternative(value = "other")

        val row = run(stored(ReviewState.CONFIRMED), fresh("old", listOf(alt))).toPersist.single()

        assertThat(row.alternatives).containsExactly(alt)
    }

    @Test
    fun `the old flags still imply the review state for a caller that only knows them`() {
        fun state(confirmed: Boolean = false, deleted: Boolean = false, source: ValueSource = ValueSource.MACHINE) =
            ExtractedData(
                "i", "d", "n", "v", ExtractedFieldType.TEXT, 0.9f,
                isConfirmed = confirmed, deletedByUser = deleted, source = source,
            ).reviewState

        assertThat(state()).isEqualTo(ReviewState.UNREVIEWED)
        assertThat(state(confirmed = true)).isEqualTo(ReviewState.CONFIRMED)
        assertThat(state(confirmed = true, source = ValueSource.USER)).isEqualTo(ReviewState.EDITED)
        assertThat(state(deleted = true, confirmed = true, source = ValueSource.USER)).isEqualTo(ReviewState.IGNORED)
    }

    @Test
    fun `an edit confirms an unchanged value and edits a changed one, keeping the flags in step`() {
        val base = stored(ReviewState.UNREVIEWED)

        val confirmed = merge.applyUserEdit(base, "old", 1L, newId).first
        val edited = merge.applyUserEdit(base, "changed", 1L, newId).first

        assertThat(confirmed.reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(edited.reviewState).isEqualTo(ReviewState.EDITED)
        assertThat(listOf(confirmed, edited).map { it.isConfirmed }).containsExactly(true, true)
    }

    @Test
    fun `ignoring sets the tombstone and restoring clears it`() {
        val ignored = merge.applyUserDelete(stored(ReviewState.CONFIRMED), 1L)
        assertThat(ignored.reviewState).isEqualTo(ReviewState.IGNORED)
        assertThat(ignored.deletedByUser).isTrue()

        val restored = merge.applyReviewState(ignored, ReviewState.UNREVIEWED, 2L)
        assertThat(restored.reviewState).isEqualTo(ReviewState.UNREVIEWED)
        assertThat(restored.deletedByUser).isFalse()
        assertThat(restored.isConfirmed).isFalse()
    }

    @Test
    fun `accepting the machine's newer reading confirms it`() {
        val flagged = stored(ReviewState.EDITED).copy(machineValue = "new", hasUnreviewedMachineChange = true)

        val accepted = merge.acceptMachineValue(flagged, 1L, newId).first

        assertThat(accepted.reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(accepted.fieldValue).isEqualTo("new")
    }
}
