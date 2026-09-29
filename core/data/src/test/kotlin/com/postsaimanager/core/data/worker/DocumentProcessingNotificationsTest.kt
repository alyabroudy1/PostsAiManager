package com.postsaimanager.core.data.worker

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import org.junit.jupiter.api.Test

class DocumentProcessingNotificationsTest {

    private fun running(stage: ProcessingStage, page: Int? = null, total: Int? = null) =
        ProcessingState.Running(
            documentId = "doc-1",
            stage = stage,
            progress = 0.5f,
            currentPage = page,
            totalPages = total,
        )

    @Test
    fun `without the app lock the line describes progress`() {
        val text = DocumentProcessingNotifications.progressText(
            running(ProcessingStage.READ, page = 2, total = 5),
            discreet = false,
        )

        assertThat(text).isEqualTo("Reading page 2 of 5")
        assertThat(DocumentProcessingNotifications.progressText(null, discreet = false))
            .isEqualTo("Starting…")
    }

    @Test
    fun `with the app lock every state gets the same generic line`() {
        val texts = ProcessingStage.entries.map {
            DocumentProcessingNotifications.progressText(
                running(it, page = 2, total = 5),
                discreet = true,
            )
        } + DocumentProcessingNotifications.progressText(null, discreet = true)

        assertThat(texts.toSet()).containsExactly(DocumentProcessingNotifications.DISCREET_TEXT)
    }

    @Test
    fun `the title is generic`() {
        assertThat(DocumentProcessingNotifications.TITLE).isEqualTo("Reading your document…")
    }
}
