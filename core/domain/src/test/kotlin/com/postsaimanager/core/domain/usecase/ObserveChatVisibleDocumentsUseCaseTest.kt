package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.testDocument
import org.junit.jupiter.api.Test

class ObserveChatVisibleDocumentsUseCaseTest {

    private fun visible(type: String?, topics: List<String> = emptyList(), deletedAt: Long? = null) =
        ObserveChatVisibleDocumentsUseCase.isChatVisible(testDocument(extractionType = type, deletedAt = deletedAt).copy(topics = topics))

    @Test
    fun `a legacy health document is hidden`() {
        assertThat(visible("health")).isFalse()
        assertThat(visible(" Health ")).isFalse()
    }

    @Test
    fun `a medical document is hidden`() {
        assertThat(visible("medical")).isFalse()
    }

    @Test
    fun `a document with the health topic is hidden whatever its family`() {
        assertThat(visible("invoice_bill", listOf("insurance", "health"))).isFalse()
        assertThat(visible("official_letter", listOf("health"))).isFalse()
    }

    @Test
    fun `an invoice is visible`() {
        assertThat(visible("invoice_bill")).isTrue()
        assertThat(visible("bill")).isTrue()
        assertThat(visible("invoice_bill", listOf("telecom"))).isTrue()
    }

    @Test
    fun `a document nothing has read yet is visible`() {
        assertThat(visible(null)).isTrue()
    }

    @Test
    fun `a trashed document is hidden`() {
        assertThat(visible("invoice_bill", deletedAt = 1L)).isFalse()
    }
}
