package com.postsaimanager.core.data.worker

import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.EnrichmentTicket
import org.junit.jupiter.api.Test

/** The ticket travels in the work's input as one JSON value; a second stage queued without one runs with none, and the pipeline rebuilds it. */
class DocumentEnrichmentWorkerTicketTest {

    private val ticket = EnrichmentTicket(
        typeId = "invoice_bill", takenIds = listOf("A1", "M2"), established = "sender: M1 «Stadtwerke»", topics = listOf("housing_utilities"),
        facts = mapOf("sender" to "Stadtwerke", "amount" to "64,98 €"), takenValues = listOf("64,98 €"),
    )

    @Test
    fun `a ticket survives the work's input exactly`() {
        val request = DocumentEnrichmentWorker.request("doc-1", ticket)
        assertThat(DocumentEnrichmentWorker.ticketOf(request.workSpec.input)).isEqualTo(ticket)
        assertThat(request.workSpec.input.getString(DocumentEnrichmentWorker.KEY_DOCUMENT_ID)).isEqualTo("doc-1")
    }

    @Test
    fun `work queued without a ticket has none, so the pipeline rebuilds it`() {
        val request = DocumentEnrichmentWorker.request("doc-1", null)
        assertThat(DocumentEnrichmentWorker.ticketOf(request.workSpec.input)).isNull()
    }

    @Test
    fun `an unreadable ticket is as good as none`() {
        val data = workDataOf(DocumentEnrichmentWorker.KEY_DOCUMENT_ID to "doc-1", DocumentEnrichmentWorker.KEY_TICKET to "{not json")
        assertThat(DocumentEnrichmentWorker.ticketOf(data)).isNull()
    }

    @Test
    fun `work queued by the build before the ticket carried its facts is still read`() {
        val data = workDataOf(
            DocumentEnrichmentWorker.KEY_DOCUMENT_ID to "doc-1",
            DocumentEnrichmentWorker.KEY_TYPE_ID to "bill",
            DocumentEnrichmentWorker.KEY_TAKEN_IDS to arrayOf("A1"),
            DocumentEnrichmentWorker.KEY_ESTABLISHED to "sender: M1",
        )
        assertThat(DocumentEnrichmentWorker.ticketOf(data)).isEqualTo(EnrichmentTicket(typeId = "bill", takenIds = listOf("A1"), established = "sender: M1"))
    }
}
