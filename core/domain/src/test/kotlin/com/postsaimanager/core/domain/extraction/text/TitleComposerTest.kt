package com.postsaimanager.core.domain.extraction.text

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.DocumentTitlePolicy
import org.junit.jupiter.api.Test

class TitleComposerTest {

    @Test
    fun `family sender and subject make a coded title with three positional args`() {
        val t = TitleComposer.compose("invoice_bill", "Musterfirma GmbH", "Rechnung 2026-08-771204")!!
        assertThat(t.code).isEqualTo("composed")
        assertThat(t.args).containsExactly("invoice_bill", "Musterfirma GmbH", "Rechnung 2026-08-771204").inOrder()
        assertThat(t.title).isEqualTo("Invoice bill · Musterfirma GmbH · Rechnung 2026-08-771204")
    }

    @Test
    fun `empty slots are dropped from the title but keep their position in the args`() {
        val noSender = TitleComposer.compose("official_letter", null, "Mahnung")!!
        assertThat(noSender.title).isEqualTo("Official letter · Mahnung")
        assertThat(noSender.args).containsExactly("official_letter", "", "Mahnung").inOrder()

        val onlyFamily = TitleComposer.compose("free_form", "  ", "")!!
        assertThat(onlyFamily.title).isEqualTo("Free form")
        assertThat(onlyFamily.args).containsExactly("free_form", "", "").inOrder()
    }

    @Test
    fun `a caller's family label replaces the fallback words`() {
        val t = TitleComposer.compose("invoice_bill", "Stadtwerke", null) { "Rechnung" }!!
        assertThat(t.title).isEqualTo("Rechnung · Stadtwerke")
    }

    @Test
    fun `whitespace is collapsed and long slots are cut`() {
        val t = TitleComposer.compose("receipt", "Bäckerei\n  Müller", "x".repeat(200))!!
        assertThat(t.args[1]).isEqualTo("Bäckerei Müller")
        assertThat(t.args[2]).hasLength(TitleComposer.MAX_SUBJECT_CHARS)
    }

    @Test
    fun `nothing to call the document gives no title`() {
        assertThat(TitleComposer.compose(null, " ", null)).isNull()
    }

    @Test
    fun `a composed title may be re-composed by a newer extractor`() {
        assertThat(DocumentTitlePolicy.modelTitleMayReplace(isUserTitle = false, titleCode = TitleComposer.CODE)).isTrue()
        assertThat(TitleComposer.isComposed(TitleComposer.CODE)).isTrue()
    }

    @Test
    fun `a user title is never touched, composed code or not`() {
        assertThat(DocumentTitlePolicy.modelTitleMayReplace(isUserTitle = true, titleCode = TitleComposer.CODE)).isFalse()
    }

    @Test
    fun `a legacy real-words title is never touched`() {
        assertThat(DocumentTitlePolicy.modelTitleMayReplace(isUserTitle = false, titleCode = null)).isFalse()
        assertThat(TitleComposer.isComposed(null)).isFalse()
    }
}
