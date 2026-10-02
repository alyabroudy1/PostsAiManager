package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import org.junit.jupiter.api.Test

/** A second stage that keeps failing settles on the template summary after [EnrichmentRetryPolicy.MAX_ATTEMPTS] runs. */
class EnrichmentRetryPolicyTest {

    private val document = Document(
        id = "d", title = "t", sourceType = SourceType.CAMERA, createdAt = 1, modifiedAt = 1, extractionType = "invoice_bill",
    )

    private fun field(slot: String, value: String, state: ReviewState = ReviewState.UNREVIEWED) = ExtractedData(
        id = "f-$slot", documentId = "d", fieldName = slot, fieldValue = value, fieldType = ExtractedFieldType.TEXT, confidence = 0.8f,
        slotKey = slot, reviewState = state, deletedByUser = state == ReviewState.IGNORED,
    )

    private val fields = listOf(field("sender", "Stadtwerke Beispiel"), field("total", "64,98 €"), field("subject", "Rechnung Juli"))

    @Test
    fun `a failed run is counted and a summary stays pending until the limit`() {
        var doc = document
        for (run in 1 until EnrichmentRetryPolicy.MAX_ATTEMPTS) {
            doc = EnrichmentRetryPolicy.afterFailure(doc, fields)
            assertThat(doc.enrichmentAttempts).isEqualTo(run)
            assertThat(doc.summarySource).isNull()
            assertThat(doc.summaryCode).isNull()
        }
    }

    @Test
    fun `the last attempt stores the template summary rendered from the verified fields`() {
        var doc = document
        repeat(EnrichmentRetryPolicy.MAX_ATTEMPTS) { doc = EnrichmentRetryPolicy.afterFailure(doc, fields) }

        assertThat(doc.enrichmentAttempts).isEqualTo(EnrichmentRetryPolicy.MAX_ATTEMPTS)
        assertThat(doc.summarySource).isEqualTo(SummarySource.TEMPLATE)
        assertThat(doc.summaryCode).isEqualTo(SummaryWriter.TEMPLATE_CODE)
        assertThat(doc.summary).isNull()
        // family, sender, addressee, amount, due date, subject
        assertThat(doc.summaryArgs).containsExactly("invoice_bill", "Stadtwerke Beispiel", "", "64,98 €", "", "Rechnung Juli").inOrder()
    }

    @Test
    fun `a value a person ignored is no fact of the template`() {
        var doc = document
        val ignored = fields.map { if (it.slotKey == "sender") field("sender", "Stadtwerke Beispiel", ReviewState.IGNORED) else it }
        repeat(EnrichmentRetryPolicy.MAX_ATTEMPTS) { doc = EnrichmentRetryPolicy.afterFailure(doc, ignored) }

        assertThat(doc.summaryArgs[1]).isEmpty()
    }

    @Test
    fun `a summary the document already has is never replaced by the template`() {
        for (source in listOf(SummarySource.USER, SummarySource.MODEL)) {
            var doc = document.copy(summary = "Mine", summarySource = source)
            repeat(EnrichmentRetryPolicy.MAX_ATTEMPTS + 1) { doc = EnrichmentRetryPolicy.afterFailure(doc, fields) }
            assertThat(doc.summary).isEqualTo("Mine")
            assertThat(doc.summarySource).isEqualTo(source)
        }
    }
}
