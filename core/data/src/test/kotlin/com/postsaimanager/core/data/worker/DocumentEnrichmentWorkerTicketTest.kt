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
    fun `a blocked second stage is queued again after a short fixed delay with its own input, never an exponential backoff`() {
        val first = DocumentEnrichmentWorker.request("doc-1", ticket)
        val again = DocumentEnrichmentWorker.again(first.workSpec.input, people = false)
        assertThat(again.workSpec.initialDelay).isEqualTo(60_000L)
        assertThat(DocumentEnrichmentWorker.RETRY_DELAY_SECONDS).isAtMost(5 * 60L)
        assertThat(again.workSpec.backoffPolicy).isEqualTo(androidx.work.BackoffPolicy.LINEAR)
        assertThat(again.workSpec.backoffDelayDuration).isEqualTo(60_000L)
        assertThat(DocumentEnrichmentWorker.ticketOf(again.workSpec.input)).isEqualTo(ticket)
        assertThat(again.tags).contains(DocumentEnrichmentWorker.TAG)
        val people = DocumentEnrichmentWorker.again(DocumentEnrichmentWorker.peopleRequest("doc-1").workSpec.input, people = true)
        assertThat(people.tags).contains(DocumentEnrichmentWorker.PEOPLE_TAG)
        assertThat(people.tags).doesNotContain(DocumentEnrichmentWorker.TAG)
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
