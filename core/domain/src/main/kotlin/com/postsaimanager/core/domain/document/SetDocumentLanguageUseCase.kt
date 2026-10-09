package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import java.util.Locale
import javax.inject.Inject

/**
 * The languages a letter can be set to by hand, as data: a new one is one more tag (and its words come from the platform). Version 1
 * reads German, English and Arabic; other countries follow by data only.
 */
object LetterLanguages {

    val SUPPORTED: List<String> = listOf("de", "en", "ar")

    /**
     * [text] as a language tag (`de`, `pt-br` -> `pt`), lower case; null when it is not one. Only the shape is checked here (two or three
     * letters, optionally followed by a region): what a language is called, or which one a letter is in, is never decided by code.
     */
    fun tagOf(text: String?): String? {
        val tag = text?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return null
        return tag.takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } }
    }
}

/**
 * The user sets the language of a letter. It is stored as theirs, so a re-read neither replaces it nor blanks it, and it is the language
 * the summary and the answers are written in from then on.
 */
class SetDocumentLanguageUseCase @Inject constructor(private val documents: DocumentRepository) {

    suspend operator fun invoke(documentId: String, language: String): PamResult<Unit> {
        val tag = LetterLanguages.tagOf(language) ?: return PamResult.Error(PamError.ValidationError("language", "not a language tag"))
        return documents.setLanguageByUser(documentId, tag)
    }
}
