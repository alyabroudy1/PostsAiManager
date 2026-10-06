package com.postsaimanager.debug

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.usecase.CreateDocumentFromPagesUseCase
import com.postsaimanager.core.model.SourceType
import java.io.File

/**
 * DEBUG BUILDS ONLY (lives in `app/src/debug`, so nothing of it is in a release build). Turns image files the test harness pushed into
 * an inbox folder into ONE multi-page document, through [CreateDocumentFromPagesUseCase] (what the scanner's result goes through), so a
 * device test needs neither the camera nor the ML Kit scanner nor a photo picker.
 *
 * The pages are copied into [pagesDir] (the app's own storage) first, because the inbox files are deleted after a successful import.
 */
class DebugImporter(
    private val inbox: File,
    private val pagesDir: File,
    private val createDocument: CreateDocumentFromPagesUseCase,
) {

    sealed interface Outcome {
        data class Imported(val documentId: String, val pages: Int) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /** @param names the inbox files, in page order (page 1 first) */
    suspend fun import(names: List<String>): Outcome {
        if (names.isEmpty()) return Outcome.Rejected("no files named")
        names.firstOrNull { !isPlainName(it) }?.let { return Outcome.Rejected("not a plain file name: $it") }
        val sources = names.map { File(inbox, it) }
        sources.firstOrNull { !it.isFile }?.let { return Outcome.Rejected("not in the inbox: ${it.name}") }

        pagesDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val copies = sources.mapIndexed { i, source -> source.copyTo(File(pagesDir, "debug-import-$stamp-${i + 1}-${source.name}"), overwrite = true) }
        return when (val result = createDocument(copies.map { it.toURI().toString() }, SourceType.UPLOAD)) {
            is PamResult.Success -> {
                sources.forEach { it.delete() }
                Outcome.Imported(result.data, copies.size)
            }
            is PamResult.Error -> {
                copies.forEach { it.delete() }
                Outcome.Rejected("the document was not stored: ${result.error}")
            }
        }
    }

    companion object {
        /** A file name and nothing else: no path separator, no `..`, not empty. */
        fun isPlainName(name: String): Boolean =
            name.isNotBlank() && '/' !in name && '\\' !in name && ".." !in name && name != "."
    }
}
