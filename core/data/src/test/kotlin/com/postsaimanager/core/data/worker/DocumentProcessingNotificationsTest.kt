package com.postsaimanager.core.data.worker

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.data.R
import com.postsaimanager.core.data.worker.DocumentProcessingNotifications.Text
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import io.mockk.every
import io.mockk.mockk
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

        assertThat(text).isEqualTo(Text(R.string.processing_notification_reading_page, listOf(2, 5)))
        assertThat(DocumentProcessingNotifications.progressText(null, discreet = false))
            .isEqualTo(Text(R.string.processing_notification_starting))
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
    fun `the title is generic and comes from a resource`() {
        assertThat(DocumentProcessingNotifications.TITLE).isEqualTo(R.string.processing_notification_title)
    }

    @Test
    fun `a line is rendered from its resource with its arguments`() {
        val context = mockk<Context>()
        every { context.getString(R.string.processing_notification_reading_page, 2, 5) } returns "Seite 2 von 5"

        assertThat(Text(R.string.processing_notification_reading_page, listOf(2, 5)).resolve(context))
            .isEqualTo("Seite 2 von 5")
    }
}
