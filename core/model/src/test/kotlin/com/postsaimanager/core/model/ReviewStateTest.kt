package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReviewStateTest {

    @Test
    fun `a confirmation that set the source to USER without changing the value is CONFIRMED`() {
        assertThat(ReviewState.fromFlags(isConfirmed = true, deletedByUser = false, source = ValueSource.USER, valueChanged = false))
            .isEqualTo(ReviewState.CONFIRMED)
    }

    @Test
    fun `a confirmed person's value that differs from the machine's is EDITED`() {
        assertThat(ReviewState.fromFlags(isConfirmed = true, deletedByUser = false, source = ValueSource.USER, valueChanged = true))
            .isEqualTo(ReviewState.EDITED)
    }

    @Test
    fun `a tombstone wins, and an untouched machine value is UNREVIEWED`() {
        assertThat(ReviewState.fromFlags(true, true, ValueSource.USER)).isEqualTo(ReviewState.IGNORED)
        assertThat(ReviewState.fromFlags(false, false, ValueSource.MACHINE)).isEqualTo(ReviewState.UNREVIEWED)
    }

    @Test
    fun `the default of an extracted field compares its value with the machine's`() {
        fun field(value: String) = ExtractedData(
            id = "f", documentId = "d", fieldName = "n", fieldValue = value, fieldType = ExtractedFieldType.TEXT,
            confidence = 0.9f, machineValue = "v", isConfirmed = true, source = ValueSource.USER,
        )
        assertThat(field("v").reviewState).isEqualTo(ReviewState.CONFIRMED)
        assertThat(field("w").reviewState).isEqualTo(ReviewState.EDITED)
    }
}
