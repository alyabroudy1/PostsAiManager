package com.postsaimanager.core.domain.document

/**
 * The port through which a feature renders a document's pages into a shareable PDF.
 *
 * Speaks only in paths, never in `android.graphics.Bitmap`, `android.net.Uri` or
 * `java.io.File` — `:core:domain` must not import `android.*` (architecture rule 4b), and
 * keeping `File` out too means this interface stays testable off-device with plain strings.
 * `:core:data`'s `PdfGenerator` is the only implementation and owns every Android type the
 * job actually needs — bitmap decoding, `android.graphics.pdf.PdfDocument`, the app's cache
 * directory.
 */
interface DocumentExporter {

    /**
     * Renders the images at [imagePaths], in order, into a single PDF named [outputName].
     *
     * @return the absolute path of the generated PDF, or null if generation failed (an image
     *   could not be decoded, or the PDF could not be written).
     */
    fun exportPdf(imagePaths: List<String>, outputName: String): String?
}
