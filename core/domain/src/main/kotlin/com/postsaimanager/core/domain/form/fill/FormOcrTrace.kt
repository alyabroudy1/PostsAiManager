package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.model.OcrBlock

/**
 * Captures the stored OCR lines of a form when its reading starts, so a device's real form can become a test fixture. It carries
 * the form's TEXT, so it is off for every document except the ones a developer asks for (see [AllowlistedFormOcrTrace]).
 *
 * To capture a form on a debug build: put the document's id on its own line in the app-private file `files/debug-trace-docs.txt`
 * (the same file the extraction's content traces use; `adb shell run-as <package> sh -c 'echo <id> >> files/debug-trace-docs.txt'`),
 * start the form fill, then read `adb logcat -s FormOcr`. Each line is `page=<n> box=<l>,<t>,<r>,<b> text=<ocr text>` with the box
 * normalised to the page (0..1). Release builds and documents not in the file log nothing.
 */
fun interface FormOcrTrace {

    /** The OCR [pages] of [documentId] a reading starts from. */
    fun lines(documentId: String, pages: List<List<OcrBlock>>)

    companion object {
        /** Reports nothing (release builds and tests). */
        val NONE = FormOcrTrace { _, _ -> }
    }
}

/**
 * The [FormOcrTrace] that reports to [sink] only when [enabled] (a debuggable build) and [documentId][lines] is in [allowed]
 * (the ids of the developer's allowlist file, one per line).
 */
class AllowlistedFormOcrTrace(
    private val enabled: () -> Boolean,
    private val allowed: () -> List<String>,
    private val sink: (String) -> Unit,
) : FormOcrTrace {

    override fun lines(documentId: String, pages: List<List<OcrBlock>>) {
        if (!enabled() || allowed().none { it.trim() == documentId }) return
        sink("document=$documentId pages=${pages.size} blocks=${pages.sumOf { it.size }}")
        pages.forEachIndexed { index, blocks ->
            blocks.forEach { block ->
                val b = block.bounds
                sink("page=${index + 1} box=${b.left.fmt()},${b.top.fmt()},${b.right.fmt()},${b.bottom.fmt()} text=${block.text.replace('\n', ' ')}")
            }
        }
    }

    private fun Float.fmt(): String = "%.3f".format(java.util.Locale.ROOT, this)
}
