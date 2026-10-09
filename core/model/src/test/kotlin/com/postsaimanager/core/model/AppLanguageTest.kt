package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppLanguageTest {

    @Test
    fun `the stored language list maps to the picker's choice`() {
        assertThat(AppLanguage.fromTags("")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTags("de")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.fromTags("ar")).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppLanguage.fromTags("en")).isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun `only the first tag counts and a region does not matter`() {
        assertThat(AppLanguage.fromTags("ar-EG,en")).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppLanguage.fromTags("de_AT")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.fromTags("DE-ch")).isEqualTo(AppLanguage.GERMAN)
    }

    @Test
    fun `a language the app is not translated into is the system choice`() {
        assertThat(AppLanguage.fromTags("fr")).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test
    fun `the tags handed to the platform round-trip`() {
        AppLanguage.entries.forEach { assertThat(AppLanguage.fromTags(it.tag)).isEqualTo(it) }
    }

    @Test
    fun `the effective language is the pick, or the phone's when translated, or English`() {
        assertThat(AppLanguage.effective(AppLanguage.ARABIC, "de-DE")).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppLanguage.effective(AppLanguage.SYSTEM, "de-DE")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.effective(AppLanguage.SYSTEM, "ar")).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppLanguage.effective(AppLanguage.SYSTEM, "fr-FR")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.effective(AppLanguage.SYSTEM, "")).isEqualTo(AppLanguage.ENGLISH)
    }
}
