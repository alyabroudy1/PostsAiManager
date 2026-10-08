package com.postsaimanager.core.domain.reading

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ReadingStage
import com.postsaimanager.core.model.ReadingStep
import com.postsaimanager.core.model.SourceType
import org.junit.jupiter.api.Test

class ReadingStepsTest {

    private fun doc(status: DocumentStatus, stage: ReadingStage?) = Document(
        id = "d", title = "t", status = status, sourceType = SourceType.CAMERA, createdAt = 0L, modifiedAt = 0L, readingStage = stage,
    )

    @Test
    fun `a letter being read goes from Reading text to Reading details`() {
        assertThat(ReadingSteps.of(doc(DocumentStatus.PROCESSING, null))).isEqualTo(ReadingStep.READING_TEXT)
        assertThat(ReadingSteps.of(doc(DocumentStatus.PROCESSING, ReadingStage.TEXT_READY))).isEqualTo(ReadingStep.READING_DETAILS)
    }

    @Test
    fun `a letter whose first stage is stored is Almost done until the second stage is written`() {
        assertThat(ReadingSteps.of(doc(DocumentStatus.EXTRACTED, ReadingStage.FIELDS_READY))).isEqualTo(ReadingStep.ALMOST_DONE)
        assertThat(ReadingSteps.of(doc(DocumentStatus.PROCESSING, ReadingStage.FIELDS_READY))).isEqualTo(ReadingStep.ALMOST_DONE)
        assertThat(ReadingSteps.of(doc(DocumentStatus.EXTRACTED, ReadingStage.UNDERSTOOD))).isNull()
    }

    @Test
    fun `a letter with no stage is finished, whatever its status`() {
        assertThat(ReadingSteps.of(doc(DocumentStatus.EXTRACTED, null))).isNull()
        assertThat(ReadingSteps.of(doc(DocumentStatus.REVIEWED, null))).isNull()
    }

    @Test
    fun `a waiting, failed or archived letter names no step`() {
        listOf(DocumentStatus.NEW, DocumentStatus.QUEUED, DocumentStatus.FAILED, DocumentStatus.ARCHIVED).forEach { status ->
            ReadingStage.entries.forEach { stage -> assertThat(ReadingSteps.of(doc(status, stage))).isNull() }
        }
    }

    @Test
    fun `the stage names round-trip and an unknown name is no stage`() {
        ReadingStage.entries.forEach { assertThat(ReadingStage.parse(it.name)).isEqualTo(it) }
        assertThat(ReadingStage.parse("SOMETHING_ELSE")).isNull()
        assertThat(ReadingStage.parse(null)).isNull()
    }
}
