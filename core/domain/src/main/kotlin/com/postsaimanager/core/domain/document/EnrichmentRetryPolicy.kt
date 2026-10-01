package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.ExtractedData

/**
 * Bounds the retries of a reading's second stage. A second stage that cannot write a summary (the engine failed, the model wrote
 * nothing, no stored text) leaves the document awaiting one, and recovery on app start queues it again; without a limit that is a
 * model run on every start, forever. Each failed run is counted on the document ([Document.enrichmentAttempts]); at [MAX_ATTEMPTS]
 * the document settles on the template summary, rendered from its verified fields, and is no longer awaiting anything.
 *
 * Three: one more than a transient failure (a busy engine, a scan pushing the work aside) needs, few enough that a document that
 * cannot be read costs the phone three runs, not one per launch. A new reading of the document starts the count again.
 */
object EnrichmentRetryPolicy {

    const val MAX_ATTEMPTS = 3

    /**
     * [document] after one more second stage that did not settle a summary: the attempt counted and, at the limit, the template
     * summary stored (never over a summary a person wrote or one the document already has). Pure.
     */
    fun afterFailure(document: Document, fields: List<ExtractedData>): Document {
        val attempts = document.enrichmentAttempts + 1
        val counted = document.copy(enrichmentAttempts = attempts)
        if (attempts < MAX_ATTEMPTS) return counted
        if (document.summarySource != null || !ReprocessOverwritePolicy.mayOverwriteSummary(document)) return counted
        val template = SummaryWriter.templateOf(EnrichmentTicketRebuilder.factsOf(document, fields))
        return counted.copy(summary = null, summarySource = template.origin, summaryCode = template.code, summaryArgs = template.args)
    }
}
