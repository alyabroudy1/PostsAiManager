package com.postsaimanager.core.domain.document

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.LanguageSource
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The language the user sets is theirs: a re-read neither replaces it nor blanks it. */
class SetDocumentLanguageUseCaseTest {

    private val documents = FakeDocumentRepository()
    private val setLanguage = SetDocumentLanguageUseCase(documents)

    private suspend fun stored() = (documents.getDocumentById("d1") as PamResult.Success).data

    @Test
    fun `a language tag is stored as the user's, tidied`() = runTest {
        documents.seed(testDocument(id = "d1", language = "de"))

        val result = setLanguage("d1", " AR ")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(stored().language).isEqualTo("ar")
        assertThat(stored().languageSource).isEqualTo(LanguageSource.USER)
    }

    @Test
    fun `something that is not a language tag changes nothing`() = runTest {
        documents.seed(testDocument(id = "d1", language = "de"))

        assertThat(setLanguage("d1", "German language")).isInstanceOf(PamResult.Error::class.java)
        assertThat(setLanguage("d1", "")).isInstanceOf(PamResult.Error::class.java)

        assertThat(stored().language).isEqualTo("de")
        assertThat(stored().languageSource).isEqualTo(LanguageSource.MODEL)
    }

    @Test
    fun `a region is dropped from the tag`() {
        assertThat(LetterLanguages.tagOf("pt-BR")).isEqualTo("pt")
        assertThat(LetterLanguages.tagOf("en_GB")).isEqualTo("en")
        assertThat(LetterLanguages.tagOf("de")).isEqualTo("de")
        assertThat(LetterLanguages.tagOf(null)).isNull()
    }

    @Test
    fun `a re-read that finds another language leaves the user's, and one that finds none blanks nothing`() = runTest {
        documents.seed(testDocument(id = "d1", language = "de"))
        setLanguage("d1", "ar")

        val reread = ReprocessOverwritePolicy.applyLanguage(stored(), "en")
        val blank = ReprocessOverwritePolicy.applyLanguage(stored(), null)

        assertThat(reread.language).isEqualTo("ar")
        assertThat(blank.language).isEqualTo("ar")
    }
}
