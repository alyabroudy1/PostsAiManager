package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.model.ReadingStep
import org.junit.jupiter.api.Test

class ReadingStepLabelsTest {

    @Test
    fun `each step has its own words`() {
        assertThat(ReadingStepLabels.of(ReadingStep.READING_TEXT)).isEqualTo(R.string.doc_row_step_reading_text)
        assertThat(ReadingStepLabels.of(ReadingStep.READING_DETAILS)).isEqualTo(R.string.doc_row_step_reading_details)
        assertThat(ReadingStepLabels.of(ReadingStep.ALMOST_DONE)).isEqualTo(R.string.doc_row_step_almost_done)
        assertThat(ReadingStep.entries.map { ReadingStepLabels.of(it) }.toSet()).hasSize(ReadingStep.entries.size)
    }
}
