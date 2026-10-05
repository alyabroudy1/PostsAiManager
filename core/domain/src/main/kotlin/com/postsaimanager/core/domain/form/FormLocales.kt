package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.OcrBlock
import java.util.Locale

/** The language a form is written in, from the recognised blocks' language tags (what the OCR engine measured, never read from words). */
object FormLocales {

    /** Blocks tagged "und" (undetermined) say nothing. */
    private const val UNDETERMINED = "und"

    /** The most common language of the blocks of [pages], or [fallback] when none is tagged. */
    fun detect(pages: List<List<OcrBlock>>, fallback: Locale): Locale {
        val tag = pages.flatten()
            .mapNotNull { b -> b.language?.takeIf { it.isNotBlank() && !it.equals(UNDETERMINED, ignoreCase = true) } }
            .groupingBy { it.lowercase() }.eachCount()
            .maxByOrNull { it.value }?.key ?: return fallback
        return Locale.forLanguageTag(tag.replace('_', '-'))
    }
}
