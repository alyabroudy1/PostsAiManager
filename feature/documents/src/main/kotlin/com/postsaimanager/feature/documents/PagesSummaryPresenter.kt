package com.postsaimanager.feature.documents

import com.postsaimanager.core.domain.document.list.PartyFields
import com.postsaimanager.core.domain.extraction.text.SummaryWriter
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.SummarySource

/** What the app knows about the person and the phone that the Pages summary needs: whether an AI model is installed, and who "Me" is. */
data class PagesContext(
    /** Some chat model is installed. True until known, so the card does not flash "AI not installed" while it loads. */
    val aiInstalled: Boolean = true,
    /** The name on the "Me" profile; null when there is none. */
    val selfName: String? = null,
)

/** Who the document is addressed to, as the Pages card shows it. */
sealed interface PagesRecipient {
    /** The addressee is the person themself (an exact folded-name match with the "Me" profile). */
    data object You : PagesRecipient

    /** Anyone else, by the name read from the letter. */
    data class Named(val name: String) : PagesRecipient
}

/** What the card under the pages says. */
enum class PagesSummaryState {
    /** The reading is there (or there is nothing more to wait for): title, summary, sender, recipient. */
    READY,

    /** The AI is still reading the letter. */
    READING,

    /** No AI model is installed and nothing was read: offers the install. */
    AI_NOT_INSTALLED,

    /** The reading failed. */
    FAILED,
}

/**
 * The "About this document" card of the Pages tab. The title is the document's own display title, drawn by the screen; this
 * holds the rest. Each line is null when there is no data for it.
 *
 * @property summaryText the summary in words (the model's, or the person's); null when none or a template
 * @property templateArgs the args of a template summary, drawn from string resources
 * @property summarySource decides the badge; null when there is no summary
 */
data class PagesSummary(
    val state: PagesSummaryState,
    val summaryText: String? = null,
    val templateArgs: List<String>? = null,
    val summarySource: SummarySource? = null,
    val from: String? = null,
    val to: PagesRecipient? = null,
) {
    val hasSummary: Boolean get() = summaryText != null || templateArgs != null
    val hasContent: Boolean get() = hasSummary || from != null || to != null
}

/** Builds the [PagesSummary] from a document and its stored fields. Pure: it verifies (exact folded name match), it never guesses. */
object PagesSummaryPresenter {

    fun present(
        document: Document,
        fields: List<ExtractedData>,
        context: PagesContext,
        summaryComing: Boolean = false,
    ): PagesSummary {
        val live = fields.filter { !it.isIgnored }
        val text = document.summary?.takeIf { it.isNotBlank() }
        val template = text == null && document.summaryCode == SummaryWriter.TEMPLATE_CODE && document.summaryArgs.isNotEmpty()
        val summary = PagesSummary(
            state = PagesSummaryState.READY,
            summaryText = text,
            templateArgs = document.summaryArgs.takeIf { template },
            summarySource = when {
                text != null -> document.summarySource ?: SummarySource.MODEL
                template -> SummarySource.TEMPLATE
                else -> null
            },
            from = PartyFields.sender(live)?.fieldValue?.trim()?.takeIf { it.isNotEmpty() },
            to = PartyFields.addressee(live)?.fieldValue?.trim()?.takeIf { it.isNotEmpty() }?.let { PartyRecipients.of(it, context.selfName) },
        )
        return when {
            document.status == DocumentStatus.FAILED -> summary.copy(state = PagesSummaryState.FAILED)
            summary.hasContent -> summary
            !context.aiInstalled -> summary.copy(state = PagesSummaryState.AI_NOT_INSTALLED)
            summaryComing || document.status in WAITING -> summary.copy(state = PagesSummaryState.READING)
            else -> summary
        }
    }

    private val WAITING = setOf(DocumentStatus.NEW, DocumentStatus.QUEUED, DocumentStatus.PROCESSING)
}
