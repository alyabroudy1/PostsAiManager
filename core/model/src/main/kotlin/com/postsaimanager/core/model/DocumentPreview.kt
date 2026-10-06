package com.postsaimanager.core.model

/** One page of a [DocumentPreview]: the image to show and what to mark on it. */
data class PreviewPage(
    val pageNumber: Int,
    val imagePath: String,
    /** Normalised regions holding the cited passage or field; empty on every page but the cited one. */
    val highlights: List<TextBounds> = emptyList(),
    /** The page's selectable text regions in reading order; empty when the page has no recognised text. */
    val regions: List<TextRegion> = emptyList(),
)

/** One piece of a page's text that can be selected and copied: a line, or a whole block when no line boxes were stored. */
data class TextRegion(
    val text: String,
    val bounds: TextBounds,
)

/**
 * A document's pages as the in-place page preview shows them. Lives in `:core:model` so the shared
 * `PagePreviewDialog` (`:core:designsystem`) and the features that open it speak one type.
 */
data class DocumentPreview(
    val documentId: String,
    val title: String,
    val pages: List<PreviewPage>,
)
