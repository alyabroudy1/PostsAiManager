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
}
