package com.postsaimanager.feature.documents

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.TimelineCodes
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import org.junit.jupiter.api.Test

class TimelineTextTest {

    private fun event(
        code: String?,
        args: List<String> = emptyList(),
        title: String = "stored title",
        description: String? = "stored description",
        type: TimelineEventType = TimelineEventType.ENTITIES_EXTRACTED,
    ) = TimelineEvent(
        id = "e", documentId = "d", eventType = type, title = title, description = description,
        createdAt = 1, code = code, args = args,
    )

    @Test
    fun `an OCR event is its page count and confidence, not a sentence`() {
        assertThat(event(TimelineCodes.OCR_DONE, listOf("3", "91")).toText())
            .isEqualTo(TimelineText.OcrDone(pages = 3, confidencePercent = 91))
        // no page produced text: no confidence
        assertThat(event(TimelineCodes.OCR_DONE, listOf("1")).toText())
            .isEqualTo(TimelineText.OcrDone(pages = 1, confidencePercent = null))
    }

    @Test
    fun `extracted and flagged events carry a count and the label keys`() {
        assertThat(event(TimelineCodes.FIELDS_EXTRACTED, listOf("2", "total", "Zaehlernummer")).toText())
            .isEqualTo(TimelineText.FieldsExtracted(2, listOf("total", "Zaehlernummer")))
        assertThat(event(TimelineCodes.REVIEW_FLAGGED, listOf("1", "due_date")).toText())
            .isEqualTo(TimelineText.ReviewFlagged(1, listOf("due_date")))
    }

    @Test
    fun `a failure keeps its raw detail as a diagnostic`() {
        assertThat(event(TimelineCodes.PROCESSING_FAILED, description = "No pages found for document").toText())
            .isEqualTo(TimelineText.ProcessingFailed("No pages found for document"))
    }

    @Test
    fun `a row written before events were data shows the sentence it was stored with`() {
        assertThat(event(null, title = "Extracted 4 field(s)", description = "Amount, IBAN").toText())
            .isEqualTo(TimelineText.Stored("Extracted 4 field(s)", "Amount, IBAN"))
    }

    @Test
    fun `an unknown code or unreadable args fall back to the stored sentence rather than failing`() {
        assertThat(event("some_future_code", listOf("1")).toText()).isInstanceOf(TimelineText.Stored::class.java)
        assertThat(event(TimelineCodes.OCR_DONE, listOf("many")).toText()).isInstanceOf(TimelineText.Stored::class.java)
        assertThat(event(TimelineCodes.FIELDS_EXTRACTED, emptyList()).toText()).isInstanceOf(TimelineText.Stored::class.java)
    }
}
