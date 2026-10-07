package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DocumentTitleTest {

    private fun doc(title: String, code: String? = null, args: List<String> = emptyList()) = Document(
        id = "d", title = title, sourceType = SourceType.CAMERA, createdAt = 0, modifiedAt = 0,
        titleCode = code, titleArgs = args,
    )

    @Test
    fun `a default scanned title is rendered from its code and page count`() {
        val d = doc("Scanned 3 page(s)", DocumentTitleCodes.SCANNED_PAGES, listOf("3"))
        assertThat(d.displayTitle { "Gescannt: $it Seiten" }).isEqualTo("Gescannt: 3 Seiten")
    }

    @Test
    fun `a real title, an unknown code or a broken count is shown as stored`() {
        assertThat(doc("Nordlicht: Zahlungserinnerung").displayTitle { "x" }).isEqualTo("Nordlicht: Zahlungserinnerung")
        assertThat(doc("T", "future_code", listOf("3")).displayTitle { "x" }).isEqualTo("T")
        assertThat(doc("T", DocumentTitleCodes.SCANNED_PAGES, listOf("three")).displayTitle { "x" }).isEqualTo("T")
        assertThat(doc("T", DocumentTitleCodes.SCANNED_PAGES).displayTitle { "x" }).isEqualTo("T")
    }

    @Test
    fun `a composed title in a list loses its type slot and keeps the sender and the subject`() {
        val composed = doc("Official letter · Nordstern · Mahnung", DocumentTitleCodes.COMPOSED, listOf("official_letter", "Nordstern", "Mahnung"))
        assertThat(composed.titleWithoutType { "x" }).isEqualTo("Nordstern · Mahnung")
        assertThat(doc("T", DocumentTitleCodes.COMPOSED, listOf("official_letter", "Nordstern", "")).titleWithoutType { "x" }).isEqualTo("Nordstern")
        assertThat(doc("T", DocumentTitleCodes.COMPOSED, listOf("receipt", "", "Beleg 12")).titleWithoutType { "x" }).isEqualTo("Beleg 12")
    }

    @Test
    fun `a composed title with nothing after the type, and any other title, is shown as before`() {
        assertThat(doc("Official letter", DocumentTitleCodes.COMPOSED, listOf("official_letter", "", "")).titleWithoutType { "x" })
            .isEqualTo("Official letter")
        assertThat(doc("Official letter", DocumentTitleCodes.COMPOSED).titleWithoutType { "x" }).isEqualTo("Official letter")
        assertThat(doc("Mein Brief").titleWithoutType { "x" }).isEqualTo("Mein Brief")
        assertThat(doc("Scanned 3 page(s)", DocumentTitleCodes.SCANNED_PAGES, listOf("3")).titleWithoutType { "Gescannt $it" }).isEqualTo("Gescannt 3")
    }
}
