package com.postsaimanager.feature.chat

import com.postsaimanager.core.domain.usecase.CitationParser

/**
 * The citation chips to show under a reply: one per distinct (document, page) — several
 * retrieved chunks routinely come from the same page — narrowed to what the answer cites
 * (in the order it first mentions them), or every distinct source when it cites none.
 *
 * [CitationParser] needs each source labelled exactly the way `SendChatMessageUseCase`
 * showed it to the model — `"p.N"` in a document chat, `"<title>, p.N"` in a standalone one —
 * to recognise the model's citation back. A source with no page number cannot be labelled
 * that way, so it goes in `unlabelled`: never individually citable, still part of the
 * "show everything" fallback.
 */
internal fun pickVisibleSources(content: String, sources: List<ChatSource>): List<ChatSource> {
    val distinct = sources.distinctBy { it.documentId to it.pageNumber }
    if (distinct.isEmpty()) return distinct
    val labelled = distinct.mapNotNull { source ->
        val page = source.pageNumber ?: return@mapNotNull null
        val label = source.title?.let { "$it, p.$page" } ?: "p.$page"
        CitationParser.Labelled(label, source)
    }
    val unlabelled = distinct.filter { it.pageNumber == null }
    return CitationParser.pick(content, labelled, unlabelled)
}
