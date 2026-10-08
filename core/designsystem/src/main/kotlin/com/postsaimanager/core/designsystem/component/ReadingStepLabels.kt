package com.postsaimanager.core.designsystem.component

import androidx.annotation.StringRes
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.model.ReadingStep

/** The one owner of the words of a list row's reading step ("Reading text…", "Reading details…", "Almost done…"). */
object ReadingStepLabels {

    @StringRes
    fun of(step: ReadingStep): Int = when (step) {
        ReadingStep.READING_TEXT -> R.string.doc_row_step_reading_text
        ReadingStep.READING_DETAILS -> R.string.doc_row_step_reading_details
        ReadingStep.ALMOST_DONE -> R.string.doc_row_step_almost_done
    }
}
